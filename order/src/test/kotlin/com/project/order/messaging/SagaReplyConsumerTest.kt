package com.project.order.messaging

import com.project.order.domain.SagaStep
import com.project.order.service.ReplyDeadLetterService
import com.project.order.service.SagaReplyService
import com.project.order.service.dto.DeadLetterCommand
import com.project.order.service.dto.ReplyDirection
import com.project.order.service.dto.ReplyOutcome
import com.project.order.service.dto.SagaReplyCommand
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.Called
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.springframework.kafka.support.KafkaHeaders
import tools.jackson.core.JacksonException
import tools.jackson.module.kotlin.jacksonObjectMapper

private fun record(value: String, messageType: String? = "STOCK_BUY", topic: String = "saga.replies"): ConsumerRecord<String, String> =
    ConsumerRecord(topic, 0, 0L, "10", value).also { record ->
        record.headers().add("sagaId", "saga-1".toByteArray())
        messageType?.let { record.headers().add("messageType", it.toByteArray()) }
    }

private class ConsumerHarness {
    val replyService = mockk<SagaReplyService>()
    val deadLetterService = mockk<ReplyDeadLetterService>()
    val handled = slot<SagaReplyCommand>()
    val consumer = SagaReplyConsumer(replyService, deadLetterService, jacksonObjectMapper())

    init {
        every { replyService.handle(capture(handled)) } just Runs
        every { deadLetterService.alert(any()) } just Runs
    }
}

class SagaReplyConsumerTest : BehaviorSpec({

    Given("product 가 보낸 재고 차감 성공 응답") {
        val h = ConsumerHarness()
        val json = """{"sagaId":"saga-1","orderId":10,"step":"STOCK","direction":"FORWARD","outcome":"SUCCEEDED","result":{"totalPrice":400}}"""

        When("소비하면") {
            h.consumer.consume(record(json))

            Then("service DTO 로 옮겨 서비스 메서드 하나만 부른다") {
                h.handled.captured shouldBe SagaReplyCommand(
                    sagaId = "saga-1",
                    orderId = 10L,
                    step = SagaStep.STOCK,
                    direction = ReplyDirection.FORWARD,
                    outcome = ReplyOutcome.SUCCEEDED,
                    code = null,
                    totalPrice = 400L,
                    messageType = "STOCK_BUY",
                )
            }
        }
    }

    listOf(
        "필드가 없는" to """{"sagaId":"saga-1","orderId":10,"step":"POINT","direction":"FORWARD","outcome":"SUCCEEDED"}""",
        "null 인" to """{"sagaId":"saga-1","orderId":10,"step":"POINT","direction":"FORWARD","outcome":"SUCCEEDED","code":null,"result":null}""",
        "빈 객체인" to """{"sagaId":"saga-1","orderId":10,"step":"POINT","direction":"FORWARD","outcome":"SUCCEEDED","code":{},"result":{}}""",
    ).forEach { (shape, json) ->
        Given("code·result 가 $shape 응답") {
            val h = ConsumerHarness()

            When("소비하면") {
                h.consumer.consume(record(json, messageType = null))

                Then("B-3 셋 모두 값 없음으로 읽는다") {
                    h.handled.captured.code.shouldBeNull()
                    h.handled.captured.totalPrice.shouldBeNull()
                    h.handled.captured.messageType.shouldBeNull()
                    h.handled.captured.step shouldBe SagaStep.POINT
                }
            }
        }
    }

    Given("point 가 보낸 잔액 부족 실패 응답") {
        val h = ConsumerHarness()
        val json = """{"sagaId":"saga-1","orderId":10,"step":"POINT","direction":"FORWARD","outcome":"FAILED","code":"INSUFFICIENT_POINT","result":null}"""

        When("소비하면") {
            h.consumer.consume(record(json, messageType = "POINT_USE"))

            Then("참여자 코드를 그대로 서비스에 넘긴다 — 번역은 서비스가 한다") {
                h.handled.captured.outcome shouldBe ReplyOutcome.FAILED
                h.handled.captured.code shouldBe "INSUFFICIENT_POINT"
            }
        }
    }

    Given("모양이 맞지 않는 응답") {
        val h = ConsumerHarness()

        When("깨진 JSON 을 소비하면") {
            shouldThrow<JacksonException> { h.consumer.consume(record("{not json")) }

            Then("재시도 없이 DLT 로 갈 예외이고 서비스를 부르지 않는다") {
                verify { h.replyService wasNot Called }
            }
        }

        When("모르는 step 을 소비하면") {
            shouldThrow<IllegalArgumentException> {
                h.consumer.consume(record("""{"sagaId":"saga-1","orderId":10,"step":"SHIPPING","direction":"FORWARD","outcome":"SUCCEEDED"}"""))
            }

            Then("재시도 없이 DLT 로 갈 IllegalArgumentException 이다") {
                verify { h.replyService wasNot Called }
            }
        }
    }

    Given("서비스가 락 대기 초과로 실패하는 응답") {
        val h = ConsumerHarness()
        every { h.replyService.handle(any()) } throws IllegalStateException("lock wait timeout")

        When("소비하면") {
            val exception = shouldThrow<IllegalStateException> {
                h.consumer.consume(record("""{"sagaId":"saga-1","orderId":10,"step":"PAYMENT","direction":"FORWARD","outcome":"SUCCEEDED"}"""))
            }

            Then("삼키지 않고 재시도에 넘긴다") {
                exception.message shouldBe "lock wait timeout"
            }
        }
    }

    Given("saga.replies-dlt 에 도착한 레코드") {
        val h = ConsumerHarness()
        val dead = record("{not json", messageType = "POINT_USE", topic = "saga.replies-dlt").also {
            it.headers().add(KafkaHeaders.EXCEPTION_CAUSE_FQCN, "tools.jackson.core.JacksonException".toByteArray())
            it.headers().add(KafkaHeaders.EXCEPTION_MESSAGE, "Unexpected character".toByteArray())
        }

        When("DLT 핸들러가 받으면") {
            h.consumer.onDeadLetter(dead)

            Then("토픽·키·sagaId·messageType·예외를 서비스로 넘긴다") {
                verify(exactly = 1) {
                    h.deadLetterService.alert(
                        DeadLetterCommand(
                            topic = "saga.replies-dlt",
                            orderId = "10",
                            sagaId = "saga-1",
                            messageType = "POINT_USE",
                            exceptionClass = "tools.jackson.core.JacksonException",
                            exceptionMessage = "Unexpected character",
                        ),
                    )
                }
            }
        }
    }

    Given("원인 예외 헤더 없이 DLT 에 도착한 레코드") {
        val h = ConsumerHarness()
        val dead = record("{}", messageType = null, topic = "saga.replies-dlt").also {
            it.headers().add(KafkaHeaders.EXCEPTION_FQCN, "org.springframework.kafka.listener.ListenerExecutionFailedException".toByteArray())
        }

        When("DLT 핸들러가 받으면") {
            h.consumer.onDeadLetter(dead)

            Then("바깥 예외 이름으로 알린다") {
                verify(exactly = 1) {
                    h.deadLetterService.alert(match { it.exceptionClass == "org.springframework.kafka.listener.ListenerExecutionFailedException" && it.messageType == null })
                }
            }
        }
    }
})
