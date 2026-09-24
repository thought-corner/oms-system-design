package com.project.product.service

import com.project.product.domain.OutboxMessage
import com.project.product.domain.OutboxStatus
import com.project.product.fixture.ProductFixture
import com.project.product.repository.OutboxMessageRepository
import com.project.product.service.dto.BuyResult
import com.project.product.service.dto.ProductCommandType
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.ZoneId
import java.util.UUID

private val FIXED_CLOCK: Clock =
    Clock.fixed(ProductFixture.SEED_TIME.atZone(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault())

class SagaReplyWriterTest : BehaviorSpec({

    val objectMapper = JsonMapper.builder().build()

    Given("재고 차감에 성공한 사가") {
        val outboxMessageRepository = mockk<OutboxMessageRepository>()
        val saved = slot<OutboxMessage>()
        every { outboxMessageRepository.save(capture(saved)) } answers { firstArg() }
        val writer = SagaReplyWriter(outboxMessageRepository, objectMapper, FIXED_CLOCK)

        When("성공 응답을 남기면") {
            writer.succeeded(ProductCommandType.STOCK_BUY, "saga-1", 10L, BuyResult(600L))
            val payload = objectMapper.readTree(saved.captured.payload)

            Then("saga.replies 로 가는 outbox 행을 키 orderId, 헤더 sagaId·커맨드 messageType 으로 넣는다") {
                saved.captured.topic shouldBe "saga.replies"
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

            Then("본문은 STOCK·FORWARD·SUCCEEDED 에 기존 성공 응답 본문을 result 로 싣고 code 는 없다") {
                payload.get("sagaId").asString() shouldBe "saga-1"
                payload.get("orderId").asLong() shouldBe 10L
                payload.get("step").asString() shouldBe "STOCK"
                payload.get("direction").asString() shouldBe "FORWARD"
                payload.get("outcome").asString() shouldBe "SUCCEEDED"
                payload.get("result").get("totalPrice").asLong() shouldBe 600L
                payload.has("code") shouldBe false
            }
        }
    }

    Given("재고가 부족해 차감이 롤백된 사가") {
        val outboxMessageRepository = mockk<OutboxMessageRepository>()
        val saved = slot<OutboxMessage>()
        every { outboxMessageRepository.save(capture(saved)) } answers { firstArg() }
        val writer = SagaReplyWriter(outboxMessageRepository, objectMapper, FIXED_CLOCK)

        When("실패 응답을 두 번 남기면") {
            writer.failed(ProductCommandType.STOCK_BUY, "saga-2", 11L, "INSUFFICIENT_STOCK")
            val firstMessageId = saved.captured.messageId
            writer.failed(ProductCommandType.STOCK_BUY, "saga-2", 11L, "INSUFFICIENT_STOCK")
            val payload = objectMapper.readTree(saved.captured.payload)

            Then("행마다 다른 message_id 를 갖는다") {
                saved.captured.messageId shouldNotBe firstMessageId
            }

            Then("본문은 FAILED 에 자기 코드를 싣고 result 는 없다") {
                saved.captured.messageType shouldBe "STOCK_BUY"
                payload.get("outcome").asString() shouldBe "FAILED"
                payload.get("code").asString() shouldBe "INSUFFICIENT_STOCK"
                payload.has("result") shouldBe false
            }
        }
    }
})
