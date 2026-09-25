package com.project.point.service

import com.project.message.point.SagaDirection
import com.project.message.point.SagaOutcome
import com.project.message.point.SagaReply as SagaReplyMessage
import com.project.message.point.SagaStep
import com.project.point.domain.OutboxMessage
import com.project.point.domain.OutboxStatus
import com.project.point.fixture.PointFixture
import com.project.point.repository.OutboxMessageRepository
import com.project.point.service.dto.PointMessageType
import com.project.point.service.dto.SagaReply
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import java.time.Clock
import java.time.ZoneId
import java.util.UUID

class SagaReplyOutboxTest : BehaviorSpec({

    val clock = Clock.fixed(PointFixture.SEED_TIME.atZone(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault())

    Given("잔액 부족으로 실패한 포인트 사용") {
        val repository = mockk<OutboxMessageRepository>()
        val saved = slot<OutboxMessage>()
        every { repository.save(capture(saved)) } answers { firstArg() }
        val outbox = SagaReplyOutbox(repository, clock)

        When("실패 응답을 넣으면") {
            outbox.append(PointMessageType.POINT_USE, SagaReply.failed(PointMessageType.POINT_USE, "saga-1", 10L, "INSUFFICIENT_POINT"))
            val payload = SagaReplyMessage.parseFrom(saved.captured.payload)

            Then("saga.replies 로 키 orderId, 헤더 값 sagaId·POINT_USE 인 행을 넣고 Protobuf 본문은 POINT 단계의 FAILED 와 code 를 싣는다") {
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
                payload.sagaId shouldBe "saga-1"
                payload.orderId shouldBe 10L
                payload.step shouldBe SagaStep.SAGA_STEP_POINT
                payload.direction shouldBe SagaDirection.SAGA_DIRECTION_FORWARD
                payload.outcome shouldBe SagaOutcome.SAGA_OUTCOME_FAILED
                payload.hasCode() shouldBe true
                payload.code shouldBe "INSUFFICIENT_POINT"
                payload.hasTotalPrice() shouldBe false
            }
        }
    }

    Given("포인트 환불") {
        val repository = mockk<OutboxMessageRepository>()
        val saved = slot<OutboxMessage>()
        every { repository.save(capture(saved)) } answers { firstArg() }
        val outbox = SagaReplyOutbox(repository, clock)

        When("성공 응답을 넣으면") {
            outbox.append(PointMessageType.POINT_CANCEL, SagaReply.succeeded(PointMessageType.POINT_CANCEL, "saga-2", 11L))
            val payload = SagaReplyMessage.parseFrom(saved.captured.payload)

            Then("POINT_CANCEL 헤더 값에 CANCEL·SUCCEEDED 를 싣고 code 와 total_price 는 비운다") {
                saved.captured.messageType shouldBe "POINT_CANCEL"
                payload.sagaId shouldBe "saga-2"
                payload.orderId shouldBe 11L
                payload.step shouldBe SagaStep.SAGA_STEP_POINT
                payload.direction shouldBe SagaDirection.SAGA_DIRECTION_CANCEL
                payload.outcome shouldBe SagaOutcome.SAGA_OUTCOME_SUCCEEDED
                payload.hasCode() shouldBe false
                payload.hasTotalPrice() shouldBe false
            }
        }
    }
})
