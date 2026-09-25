package com.project.payment.service

import com.project.message.payment.SagaDirection
import com.project.message.payment.SagaOutcome
import com.project.message.payment.SagaReply
import com.project.message.payment.SagaStep
import com.project.payment.domain.OutboxMessage
import com.project.payment.domain.OutboxStatus
import com.project.payment.exception.PaymentErrorCode
import com.project.payment.fixture.PaymentFixture
import com.project.payment.repository.OutboxMessageRepository
import com.project.payment.service.dto.PaymentMessageType
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.CapturingSlot
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import java.util.UUID

private fun outboxRepository(saved: CapturingSlot<OutboxMessage>): OutboxMessageRepository =
    mockk<OutboxMessageRepository>().also {
        every { it.save(capture(saved)) } answers { firstArg() }
    }

class SagaReplyOutboxTest : BehaviorSpec({

    Given("비즈니스 판정으로 거부된 결제") {
        val saved = slot<OutboxMessage>()
        val outbox = SagaReplyOutbox(outboxRepository(saved), PaymentFixture.FIXED_CLOCK)

        When("실패 응답을 남기면") {
            outbox.failed(PaymentMessageType.PAYMENT_PAY, "saga-f", 10L, PaymentErrorCode.ALREADY_PAID)
            val reply = SagaReply.parseFrom(saved.captured.payload)

            Then("order.reply 로 가는 FAILED 응답에 참여자 코드가 실리고 total_price 는 비어 있다") {
                saved.captured.topic shouldBe "order.reply"
                saved.captured.messageKey shouldBe "10"
                saved.captured.sagaId shouldBe "saga-f"
                saved.captured.messageType shouldBe "PAYMENT_PAY"
                reply.sagaId shouldBe "saga-f"
                reply.orderId shouldBe 10L
                reply.step shouldBe SagaStep.SAGA_STEP_PAYMENT
                reply.direction shouldBe SagaDirection.SAGA_DIRECTION_FORWARD
                reply.outcome shouldBe SagaOutcome.SAGA_OUTCOME_FAILED
                reply.hasCode() shouldBe true
                reply.code shouldBe "ALREADY_PAID"
                reply.hasTotalPrice() shouldBe false
            }
        }
    }

    Given("승인된 결제") {
        val saved = slot<OutboxMessage>()
        val outbox = SagaReplyOutbox(outboxRepository(saved), PaymentFixture.FIXED_CLOCK)

        When("성공 응답을 남기면") {
            outbox.succeeded(PaymentMessageType.PAYMENT_PAY, "saga-s", 11L)
            val reply = SagaReply.parseFrom(saved.captured.payload)

            Then("code 와 total_price 는 비어 있고 Clock 시각으로 계약 칸만 채운 PENDING 행이 된다") {
                reply.sagaId shouldBe "saga-s"
                reply.orderId shouldBe 11L
                reply.step shouldBe SagaStep.SAGA_STEP_PAYMENT
                reply.direction shouldBe SagaDirection.SAGA_DIRECTION_FORWARD
                reply.outcome shouldBe SagaOutcome.SAGA_OUTCOME_SUCCEEDED
                reply.hasCode() shouldBe false
                reply.hasTotalPrice() shouldBe false
                saved.captured.occurredAt shouldBe PaymentFixture.FIXED_NOW
                UUID.fromString(saved.captured.messageId).toString() shouldBe saved.captured.messageId
                saved.captured.status shouldBe OutboxStatus.PENDING
                saved.captured.failCount shouldBe 0
                saved.captured.publishedAt.shouldBeNull()
                saved.captured.claimedAt.shouldBeNull()
                saved.captured.failedAt.shouldBeNull()
                saved.captured.lastError.shouldBeNull()
            }
        }

        When("결제 취소의 성공 응답을 남기면") {
            outbox.succeeded(PaymentMessageType.PAYMENT_CANCEL, "saga-s", 11L)
            val reply = SagaReply.parseFrom(saved.captured.payload)

            Then("messageType 은 커맨드와 같은 PAYMENT_CANCEL, direction 은 CANCEL 이다") {
                saved.captured.messageType shouldBe "PAYMENT_CANCEL"
                reply.direction shouldBe SagaDirection.SAGA_DIRECTION_CANCEL
                reply.outcome shouldBe SagaOutcome.SAGA_OUTCOME_SUCCEEDED
                reply.hasCode() shouldBe false
            }
        }
    }
})
