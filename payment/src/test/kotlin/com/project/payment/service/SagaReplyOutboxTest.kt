package com.project.payment.service

import com.project.payment.domain.OutboxMessage
import com.project.payment.domain.OutboxStatus
import com.project.payment.exception.PaymentErrorCode
import com.project.payment.fixture.PaymentFixture
import com.project.payment.repository.OutboxMessageRepository
import com.project.payment.service.dto.PayResult
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
        val outbox = SagaReplyOutbox(outboxRepository(saved), PaymentFixture.JSON_MAPPER, PaymentFixture.FIXED_CLOCK)

        When("실패 응답을 남기면") {
            outbox.failed(PaymentMessageType.PAYMENT_PAY, "saga-f", 10L, PaymentErrorCode.ALREADY_PAID)
            val payload = PaymentFixture.JSON_MAPPER.readTree(saved.captured.payload)

            Then("saga.replies 로 가는 FAILED 응답에 참여자 코드가 실리고 result 는 비어 있다") {
                saved.captured.topic shouldBe "saga.replies"
                saved.captured.messageKey shouldBe "10"
                saved.captured.sagaId shouldBe "saga-f"
                saved.captured.messageType shouldBe "PAYMENT_PAY"
                payload.get("step").asString() shouldBe "PAYMENT"
                payload.get("direction").asString() shouldBe "FORWARD"
                payload.get("outcome").asString() shouldBe "FAILED"
                payload.get("code").asString() shouldBe "ALREADY_PAID"
                payload.get("result").size() shouldBe 0
            }
        }
    }

    Given("승인된 결제") {
        val saved = slot<OutboxMessage>()
        val outbox = SagaReplyOutbox(outboxRepository(saved), PaymentFixture.JSON_MAPPER, PaymentFixture.FIXED_CLOCK)

        When("성공 응답을 남기면") {
            outbox.succeeded(PaymentMessageType.PAYMENT_PAY, "saga-s", 11L, PayResult(5L, PaymentFixture.FIXED_PAID_AT))
            val payload = PaymentFixture.JSON_MAPPER.readTree(saved.captured.payload)

            Then("result 는 A안의 결제 응답 본문이고 code 는 없으며 Clock 시각으로 기록된다") {
                payload.get("sagaId").asString() shouldBe "saga-s"
                payload.get("orderId").asLong() shouldBe 11L
                payload.get("outcome").asString() shouldBe "SUCCEEDED"
                payload.get("code").isNull shouldBe true
                payload.get("result").get("paymentId").asLong() shouldBe 5L
                payload.get("result").get("paidAt").asString() shouldBe "2026-09-21T10:00:00"
                saved.captured.occurredAt shouldBe PaymentFixture.FIXED_PAID_AT
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
            outbox.succeeded(PaymentMessageType.PAYMENT_CANCEL, "saga-s", 11L, emptyMap<String, Any>())
            val payload = PaymentFixture.JSON_MAPPER.readTree(saved.captured.payload)

            Then("messageType 은 커맨드와 같은 PAYMENT_CANCEL, direction 은 CANCEL, result 는 빈 객체다") {
                saved.captured.messageType shouldBe "PAYMENT_CANCEL"
                payload.get("direction").asString() shouldBe "CANCEL"
                payload.get("result").isObject shouldBe true
                payload.get("result").size() shouldBe 0
            }
        }
    }
})
