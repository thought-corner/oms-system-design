package com.project.product.service

import com.project.message.product.SagaDirection
import com.project.message.product.SagaOutcome
import com.project.message.product.SagaReply
import com.project.message.product.SagaStep
import com.project.product.domain.OutboxMessage
import com.project.product.domain.OutboxStatus
import com.project.product.fixture.ProductFixture
import com.project.product.repository.OutboxMessageRepository
import com.project.product.service.dto.ProductCommandType
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import java.time.Clock
import java.time.ZoneId
import java.util.UUID

private val FIXED_CLOCK: Clock =
    Clock.fixed(ProductFixture.SEED_TIME.atZone(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault())

class SagaReplyWriterTest : BehaviorSpec({

    Given("재고 차감에 성공한 사가") {
        val outboxMessageRepository = mockk<OutboxMessageRepository>()
        val saved = slot<OutboxMessage>()
        every { outboxMessageRepository.save(capture(saved)) } answers { firstArg() }
        val writer = SagaReplyWriter(outboxMessageRepository, FIXED_CLOCK)

        When("성공 응답을 남기면") {
            writer.succeeded(ProductCommandType.STOCK_BUY, "saga-1", 10L, 600L)
            val payload = SagaReply.parseFrom(saved.captured.payload)

            Then("order.reply 로 가는 outbox 행을 키 orderId, 헤더 sagaId·커맨드 messageType 으로 넣는다") {
                saved.captured.topic shouldBe "order.reply"
                saved.captured.messageKey shouldBe "10"
                saved.captured.sagaId shouldBe "saga-1"
                saved.captured.messageType shouldBe "STOCK_BUY"
                saved.captured.occurredAt shouldBe ProductFixture.SEED_TIME
            }

            Then("서비스가 쓰는 계약 컬럼만 채운 PENDING 행이고 message_id 는 UUID 다") {
                UUID.fromString(saved.captured.messageId).toString() shouldBe saved.captured.messageId
                saved.captured.status shouldBe OutboxStatus.PENDING
                saved.captured.failCount shouldBe 0
                saved.captured.claimedAt.shouldBeNull()
                saved.captured.publishedAt.shouldBeNull()
                saved.captured.failedAt.shouldBeNull()
                saved.captured.lastError.shouldBeNull()
            }

            Then("본문은 STOCK·FORWARD·SUCCEEDED 에 총액을 total_price 로 싣고 code 는 없다") {
                payload.sagaId shouldBe "saga-1"
                payload.orderId shouldBe 10L
                payload.step shouldBe SagaStep.SAGA_STEP_STOCK
                payload.direction shouldBe SagaDirection.SAGA_DIRECTION_FORWARD
                payload.outcome shouldBe SagaOutcome.SAGA_OUTCOME_SUCCEEDED
                payload.hasTotalPrice() shouldBe true
                payload.totalPrice shouldBe 600L
                payload.hasCode() shouldBe false
            }
        }
    }

    Given("재고가 부족해 차감이 롤백된 사가") {
        val outboxMessageRepository = mockk<OutboxMessageRepository>()
        val saved = slot<OutboxMessage>()
        every { outboxMessageRepository.save(capture(saved)) } answers { firstArg() }
        val writer = SagaReplyWriter(outboxMessageRepository, FIXED_CLOCK)

        When("실패 응답을 두 번 남기면") {
            writer.failed(ProductCommandType.STOCK_BUY, "saga-2", 11L, "INSUFFICIENT_STOCK")
            val firstMessageId = saved.captured.messageId
            writer.failed(ProductCommandType.STOCK_BUY, "saga-2", 11L, "INSUFFICIENT_STOCK")
            val payload = SagaReply.parseFrom(saved.captured.payload)

            Then("행마다 다른 message_id 를 갖는다") {
                saved.captured.messageId shouldNotBe firstMessageId
            }

            Then("본문은 FAILED 에 자기 코드를 싣고 total_price 는 없다") {
                saved.captured.messageType shouldBe "STOCK_BUY"
                payload.outcome shouldBe SagaOutcome.SAGA_OUTCOME_FAILED
                payload.code shouldBe "INSUFFICIENT_STOCK"
                payload.hasTotalPrice() shouldBe false
            }
        }
    }

    Given("재고를 되돌린 사가") {
        val outboxMessageRepository = mockk<OutboxMessageRepository>()
        val saved = slot<OutboxMessage>()
        every { outboxMessageRepository.save(capture(saved)) } answers { firstArg() }
        val writer = SagaReplyWriter(outboxMessageRepository, FIXED_CLOCK)

        When("보상 성공 응답을 남기면") {
            writer.succeeded(ProductCommandType.STOCK_CANCEL, "saga-3", 12L)
            val payload = SagaReply.parseFrom(saved.captured.payload)

            Then("본문은 STOCK·CANCEL·SUCCEEDED 이고 code 도 total_price 도 없다") {
                saved.captured.messageType shouldBe "STOCK_CANCEL"
                payload.step shouldBe SagaStep.SAGA_STEP_STOCK
                payload.direction shouldBe SagaDirection.SAGA_DIRECTION_CANCEL
                payload.outcome shouldBe SagaOutcome.SAGA_OUTCOME_SUCCEEDED
                payload.hasCode() shouldBe false
                payload.hasTotalPrice() shouldBe false
            }
        }
    }
})
