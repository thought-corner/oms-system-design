package com.project.point.service

import com.project.point.domain.OutboxMessage
import com.project.point.domain.OutboxStatus
import com.project.point.fixture.PointFixture
import com.project.point.repository.OutboxMessageRepository
import com.project.point.service.dto.PointMessageType
import com.project.point.service.dto.SagaReply
import com.project.point.service.dto.UseCancelResult
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.Clock
import java.time.ZoneId
import java.util.UUID

class SagaReplyOutboxTest : BehaviorSpec({

    val clock = Clock.fixed(PointFixture.SEED_TIME.atZone(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault())
    val objectMapper = jacksonObjectMapper()

    Given("잔액 부족으로 실패한 포인트 사용") {
        val repository = mockk<OutboxMessageRepository>()
        val saved = slot<OutboxMessage>()
        every { repository.save(capture(saved)) } answers { firstArg() }
        val outbox = SagaReplyOutbox(repository, objectMapper, clock)

        When("실패 응답을 넣으면") {
            outbox.append(PointMessageType.POINT_USE, SagaReply.failed(PointMessageType.POINT_USE, "saga-1", 10L, "INSUFFICIENT_POINT"))
            val payload = objectMapper.readTree(saved.captured.payload)

            Then("saga.replies 로 키 orderId, 헤더 값 sagaId·POINT_USE 인 행을 넣고 본문은 FAILED 와 code 를 싣는다") {
                saved.captured.topic shouldBe "saga.replies"
                saved.captured.messageKey shouldBe "10"
                saved.captured.sagaId shouldBe "saga-1"
                saved.captured.messageType shouldBe "POINT_USE"
                saved.captured.occurredAt shouldBe PointFixture.SEED_TIME
                UUID.fromString(saved.captured.messageId).toString() shouldBe saved.captured.messageId
                saved.captured.status shouldBe OutboxStatus.PENDING
                saved.captured.failCount shouldBe 0
                saved.captured.claimedAt.shouldBeNull()
                saved.captured.publishedAt.shouldBeNull()
                saved.captured.failedAt.shouldBeNull()
                saved.captured.lastError.shouldBeNull()
                payload["sagaId"].asString() shouldBe "saga-1"
                payload["orderId"].asLong() shouldBe 10L
                payload["step"].asString() shouldBe "POINT"
                payload["direction"].asString() shouldBe "FORWARD"
                payload["outcome"].asString() shouldBe "FAILED"
                payload["code"].asString() shouldBe "INSUFFICIENT_POINT"
                payload["result"].isNull shouldBe true
            }
        }
    }

    Given("400을 되돌린 포인트 환불") {
        val repository = mockk<OutboxMessageRepository>()
        val saved = slot<OutboxMessage>()
        every { repository.save(capture(saved)) } answers { firstArg() }
        val outbox = SagaReplyOutbox(repository, objectMapper, clock)

        When("성공 응답을 넣으면") {
            outbox.append(
                PointMessageType.POINT_CANCEL,
                SagaReply.succeeded(PointMessageType.POINT_CANCEL, "saga-2", 11L, UseCancelResult(400L)),
            )
            val payload = objectMapper.readTree(saved.captured.payload)

            Then("POINT_CANCEL 헤더 값에 CANCEL·SUCCEEDED 와 A안 응답 본문 refundedAmount 를 싣는다") {
                saved.captured.messageType shouldBe "POINT_CANCEL"
                payload["direction"].asString() shouldBe "CANCEL"
                payload["outcome"].asString() shouldBe "SUCCEEDED"
                payload["code"].isNull shouldBe true
                payload["result"]["refundedAmount"].asLong() shouldBe 400L
            }
        }
    }
})
