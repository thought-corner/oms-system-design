package com.project.point.messaging

import com.project.common.exception.BusinessException
import com.project.point.exception.PointErrorCode
import com.project.point.service.PointService
import com.project.point.service.dto.UseCancelCommand
import com.project.point.service.dto.UseCommand
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.Called
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.springframework.dao.CannotAcquireLockException
import tools.jackson.core.JacksonException
import tools.jackson.module.kotlin.jacksonObjectMapper

private const val USE_JSON = """{"sagaId":"saga-1","orderId":10,"userId":1,"amount":400}"""
private const val CANCEL_JSON = """{"sagaId":"saga-1","orderId":10}"""
private val USE_COMMAND = UseCommand(sagaId = "saga-1", orderId = 10L, userId = 1L, amount = 400L)

private fun record(value: String, messageType: String?): ConsumerRecord<String, String> =
    ConsumerRecord("cmd.point", 0, 0L, "10", value).also { record ->
        record.headers().add(MessageHeaders.SAGA_ID, "saga-1".toByteArray())
        messageType?.let { record.headers().add(MessageHeaders.MESSAGE_TYPE, it.toByteArray()) }
    }

private fun pointService(): PointService = mockk<PointService>().also {
    every { it.recordUseFailure(any(), any()) } just Runs
}

class PointCommandConsumerTest : BehaviorSpec({

    val objectMapper = jacksonObjectMapper()

    Given("잔액이 부족한 사용자의 POINT_USE") {
        val pointService = pointService()
        every { pointService.use(USE_COMMAND) } throws BusinessException(PointErrorCode.INSUFFICIENT_POINT, "userId=1")
        val consumer = PointCommandConsumer(pointService, objectMapper)

        When("소비하면") {
            consumer.consume(record(USE_JSON, "POINT_USE"))

            Then("롤백된 사용과 별도로 실패 응답 기록을 부르고 예외를 삼켜 오프셋을 넘긴다") {
                verifyOrder {
                    pointService.use(USE_COMMAND)
                    pointService.recordUseFailure(USE_COMMAND, PointErrorCode.INSUFFICIENT_POINT)
                }
            }
        }
    }

    Given("보상이 먼저 도착한 사가의 늦은 POINT_USE") {
        val pointService = pointService()
        every { pointService.use(USE_COMMAND) } throws BusinessException(PointErrorCode.SAGA_ALREADY_COMPENSATED, "sagaId=saga-1")
        val consumer = PointCommandConsumer(pointService, objectMapper)

        When("소비하면") {
            consumer.consume(record(USE_JSON, "POINT_USE"))

            Then("SAGA_ALREADY_COMPENSATED 실패 응답을 기록한다") {
                verify(exactly = 1) { pointService.recordUseFailure(USE_COMMAND, PointErrorCode.SAGA_ALREADY_COMPENSATED) }
            }
        }
    }

    Given("DB 락 대기가 초과된 POINT_USE") {
        val pointService = pointService()
        every { pointService.use(USE_COMMAND) } throws CannotAcquireLockException("lock wait timeout")
        val consumer = PointCommandConsumer(pointService, objectMapper)

        When("소비하면") {
            val exception = shouldThrow<CannotAcquireLockException> { consumer.consume(record(USE_JSON, "POINT_USE")) }

            Then("실패 응답을 남기지 않고 예외를 재시도에 넘긴다") {
                exception.message shouldBe "lock wait timeout"
                verify(exactly = 0) { pointService.recordUseFailure(any(), any()) }
            }
        }
    }

    Given("보상 처리 중 DB 오류가 난 POINT_CANCEL") {
        val pointService = pointService()
        every { pointService.cancel(any()) } throws BusinessException(PointErrorCode.POINT_NOT_FOUND, "userId=1")
        val consumer = PointCommandConsumer(pointService, objectMapper)

        When("소비하면") {
            val exception = shouldThrow<BusinessException> { consumer.consume(record(CANCEL_JSON, "POINT_CANCEL")) }

            Then("보상은 실패 응답이 없으므로 예외를 재시도에 넘긴다") {
                exception.errorCode shouldBe PointErrorCode.POINT_NOT_FOUND
                verify(exactly = 0) { pointService.recordUseFailure(any(), any()) }
            }
        }
    }

    Given("messageType 헤더가 없거나 모르는 값인 레코드") {
        val pointService = pointService()
        val consumer = PointCommandConsumer(pointService, objectMapper)

        When("모르는 STOCK_BUY 를 소비하면") {
            val exception = shouldThrow<IllegalArgumentException> { consumer.consume(record(USE_JSON, "STOCK_BUY")) }

            Then("재시도 없이 DLT 로 갈 IllegalArgumentException 이고 서비스를 부르지 않는다") {
                exception.message shouldBe "messageType=STOCK_BUY"
                verify { pointService wasNot Called }
            }
        }

        When("헤더 없이 소비하면") {
            val exception = shouldThrow<IllegalArgumentException> { consumer.consume(record(USE_JSON, null)) }

            Then("IllegalArgumentException 이다") {
                exception.message shouldBe "messageType=null"
            }
        }
    }

    Given("모양이 맞지 않는 POINT_USE") {
        val pointService = pointService()
        val consumer = PointCommandConsumer(pointService, objectMapper)

        When("깨진 JSON 을 소비하면") {
            shouldThrow<JacksonException> { consumer.consume(record("{not json", "POINT_USE")) }

            Then("서비스를 부르지 않는다") {
                verify { pointService wasNot Called }
            }
        }

        When("amount 가 빠진 JSON 을 소비하면") {
            shouldThrow<JacksonException> { consumer.consume(record(CANCEL_JSON, "POINT_USE")) }

            Then("서비스를 부르지 않는다") {
                verify { pointService wasNot Called }
            }
        }
    }

    Given("정상 POINT_USE 와 POINT_CANCEL") {
        val pointService = pointService()
        every { pointService.use(any()) } just Runs
        every { pointService.cancel(any()) } returns 400L
        val consumer = PointCommandConsumer(pointService, objectMapper)

        When("차례로 소비하면") {
            consumer.consume(record(USE_JSON, "POINT_USE"))
            consumer.consume(record(CANCEL_JSON, "POINT_CANCEL"))

            Then("messageType 에 따라 사용과 환불을 부르고 실패 응답은 기록하지 않는다") {
                verifyOrder {
                    pointService.use(USE_COMMAND)
                    pointService.cancel(UseCancelCommand(sagaId = "saga-1", orderId = 10L))
                }
                verify(exactly = 0) { pointService.recordUseFailure(any(), any()) }
            }
        }
    }
})
