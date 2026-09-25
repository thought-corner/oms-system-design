package com.project.order.messaging

import com.google.protobuf.InvalidProtocolBufferException
import com.project.message.order.SagaDirection
import com.project.message.order.SagaOutcome
import com.project.message.order.SagaReply
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
import com.project.message.order.SagaStep as SagaStepMessage

private val BROKEN_BYTES = byteArrayOf(0x0A, 0x05, 0x73)

private fun reply(
    step: SagaStepMessage,
    direction: SagaDirection = SagaDirection.SAGA_DIRECTION_FORWARD,
    outcome: SagaOutcome = SagaOutcome.SAGA_OUTCOME_SUCCEEDED,
): SagaReply.Builder =
    SagaReply.newBuilder()
        .setSagaId("saga-1")
        .setOrderId(10L)
        .setStep(step)
        .setDirection(direction)
        .setOutcome(outcome)

private fun record(value: ByteArray?, messageType: String? = "STOCK_BUY", topic: String = "order.reply"): ConsumerRecord<String, ByteArray> =
    ConsumerRecord<String, ByteArray>(topic, 0, 0L, "10", value).also { record ->
        record.headers().add("sagaId", "saga-1".toByteArray())
        messageType?.let { record.headers().add("messageType", it.toByteArray()) }
    }

private fun record(value: SagaReply.Builder, messageType: String? = "STOCK_BUY"): ConsumerRecord<String, ByteArray> =
    record(value.build().toByteArray(), messageType)

private class ConsumerHarness {
    val replyService = mockk<SagaReplyService>()
    val deadLetterService = mockk<ReplyDeadLetterService>()
    val handled = slot<SagaReplyCommand>()
    val consumer = SagaReplyConsumer(replyService, deadLetterService)

    init {
        every { replyService.handle(capture(handled)) } just Runs
        every { deadLetterService.alert(any()) } just Runs
    }
}

class SagaReplyConsumerTest : BehaviorSpec({

    Given("product 가 보낸 재고 차감 성공 응답") {
        val h = ConsumerHarness()

        When("소비하면") {
            h.consumer.consume(record(reply(SagaStepMessage.SAGA_STEP_STOCK).setTotalPrice(400L)))

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

    Given("code·total_price 가 없는 응답") {
        val h = ConsumerHarness()

        When("소비하면") {
            h.consumer.consume(record(reply(SagaStepMessage.SAGA_STEP_POINT), messageType = null))

            Then("값 없음으로 읽는다") {
                h.handled.captured.code.shouldBeNull()
                h.handled.captured.totalPrice.shouldBeNull()
                h.handled.captured.messageType.shouldBeNull()
                h.handled.captured.step shouldBe SagaStep.POINT
            }
        }
    }

    Given("total_price 가 0 으로 명시된 결제 취소 응답") {
        val h = ConsumerHarness()

        When("소비하면") {
            h.consumer.consume(
                record(
                    reply(SagaStepMessage.SAGA_STEP_PAYMENT, direction = SagaDirection.SAGA_DIRECTION_CANCEL).setTotalPrice(0L).setCode(""),
                    messageType = "PAYMENT_CANCEL",
                ),
            )

            Then("기본값과 없음을 구분해 0 과 빈 문자열로 읽는다") {
                h.handled.captured.totalPrice shouldBe 0L
                h.handled.captured.code shouldBe ""
                h.handled.captured.direction shouldBe ReplyDirection.CANCEL
                h.handled.captured.step shouldBe SagaStep.PAYMENT
            }
        }
    }

    Given("point 가 보낸 잔액 부족 실패 응답") {
        val h = ConsumerHarness()

        When("소비하면") {
            h.consumer.consume(
                record(
                    reply(SagaStepMessage.SAGA_STEP_POINT, outcome = SagaOutcome.SAGA_OUTCOME_FAILED).setCode("INSUFFICIENT_POINT"),
                    messageType = "POINT_USE",
                ),
            )

            Then("참여자 코드를 그대로 서비스에 넘긴다 — 번역은 서비스가 한다") {
                h.handled.captured.outcome shouldBe ReplyOutcome.FAILED
                h.handled.captured.code shouldBe "INSUFFICIENT_POINT"
            }
        }
    }

    Given("모양이 맞지 않는 응답") {
        val h = ConsumerHarness()

        When("깨진 바이트를 소비하면") {
            shouldThrow<InvalidProtocolBufferException> { h.consumer.consume(record(BROKEN_BYTES)) }

            Then("재시도 없이 DLT 로 갈 예외이고 서비스를 부르지 않는다") {
                verify { h.replyService wasNot Called }
            }
        }

        When("값이 없는 레코드를 소비하면") {
            shouldThrow<IllegalArgumentException> { h.consumer.consume(record(null as ByteArray?)) }

            Then("재시도 없이 DLT 로 갈 IllegalArgumentException 이다") {
                verify { h.replyService wasNot Called }
            }
        }

        listOf(
            "step 이 빠진" to reply(SagaStepMessage.SAGA_STEP_UNSPECIFIED),
            "모르는 step 번호의" to reply(SagaStepMessage.SAGA_STEP_STOCK).setStepValue(99),
            "direction 이 빠진" to reply(SagaStepMessage.SAGA_STEP_STOCK, direction = SagaDirection.SAGA_DIRECTION_UNSPECIFIED),
            "모르는 direction 번호의" to reply(SagaStepMessage.SAGA_STEP_STOCK).setDirectionValue(99),
            "outcome 이 빠진" to reply(SagaStepMessage.SAGA_STEP_STOCK, outcome = SagaOutcome.SAGA_OUTCOME_UNSPECIFIED),
            "모르는 outcome 번호의" to reply(SagaStepMessage.SAGA_STEP_STOCK).setOutcomeValue(99),
            "sagaId 가 빠진" to reply(SagaStepMessage.SAGA_STEP_STOCK).clearSagaId(),
            "orderId 가 빠진" to reply(SagaStepMessage.SAGA_STEP_STOCK).clearOrderId(),
        ).forEach { (shape, message) ->
            When("$shape 응답을 소비하면") {
                shouldThrow<IllegalArgumentException> { h.consumer.consume(record(message)) }

                Then("재시도 없이 DLT 로 갈 IllegalArgumentException 이다") {
                    verify { h.replyService wasNot Called }
                }
            }
        }
    }

    Given("서비스가 락 대기 초과로 실패하는 응답") {
        val h = ConsumerHarness()
        every { h.replyService.handle(any()) } throws IllegalStateException("lock wait timeout")

        When("소비하면") {
            val exception = shouldThrow<IllegalStateException> {
                h.consumer.consume(record(reply(SagaStepMessage.SAGA_STEP_PAYMENT)))
            }

            Then("삼키지 않고 재시도에 넘긴다") {
                exception.message shouldBe "lock wait timeout"
            }
        }
    }

    Given("order.reply.dlt 에 도착한 레코드") {
        val h = ConsumerHarness()
        val dead = record(BROKEN_BYTES, messageType = "POINT_USE", topic = "order.reply.dlt").also {
            it.headers().add(KafkaHeaders.EXCEPTION_CAUSE_FQCN, "com.google.protobuf.InvalidProtocolBufferException".toByteArray())
            it.headers().add(KafkaHeaders.EXCEPTION_MESSAGE, "Protocol message tag had invalid wire type.".toByteArray())
        }

        When("DLT 핸들러가 받으면") {
            h.consumer.onDeadLetter(dead)

            Then("토픽·키·sagaId·messageType·예외를 서비스로 넘긴다") {
                verify(exactly = 1) {
                    h.deadLetterService.alert(
                        DeadLetterCommand(
                            topic = "order.reply.dlt",
                            orderId = "10",
                            sagaId = "saga-1",
                            messageType = "POINT_USE",
                            exceptionClass = "com.google.protobuf.InvalidProtocolBufferException",
                            exceptionMessage = "Protocol message tag had invalid wire type.",
                        ),
                    )
                }
            }
        }
    }

    Given("원인 예외 헤더 없이 DLT 에 도착한 레코드") {
        val h = ConsumerHarness()
        val dead = record(ByteArray(0), messageType = null, topic = "order.reply.dlt").also {
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
