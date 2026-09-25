package com.project.operation.service

import com.project.operation.domain.OutboxDelay
import com.project.operation.domain.OutboxFailure
import com.project.operation.domain.OutboxSource
import com.project.operation.domain.PublishFailure
import com.project.operation.fixture.OutboxFixture.message
import com.project.operation.repository.OutboxRepository
import com.project.operation.service.policy.OutboxRelayPolicy
import com.project.operation.service.policy.PublishedOutboxRetentionPolicy
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify

class OutboxServiceTest : BehaviorSpec({

    Given("발행에 실패한 세 행 중 둘째 사유가 255자를 넘고 셋째는 그새 PENDING 이 아니게 된 경우") {
        val repository = mockk<OutboxRepository>()
        val service = OutboxService(repository)
        val longError = "E".repeat(300)
        val first = message(1)
        val second = message(2)
        val third = message(3)
        val counted = slot<List<PublishFailure>>()
        every { repository.countFailures(OutboxSource.ORDER, capture(counted), OutboxRelayPolicy.MAX_PUBLISH_ATTEMPTS) } returns intArrayOf(1, 1, 0)
        val exhausted = OutboxFailure(second, OutboxRelayPolicy.MAX_PUBLISH_ATTEMPTS, "E".repeat(255))
        every { repository.findFailed(OutboxSource.ORDER, listOf(first, second)) } returns listOf(exhausted)

        When("실패를 기록하면") {
            val recorded = service.recordFailures(
                OutboxSource.ORDER,
                listOf(
                    PublishFailure(first, "broker ack timed out"),
                    PublishFailure(second, longError),
                    PublishFailure(third, "broker ack timed out"),
                ),
            )

            Then("사유를 255자로 잘라 한도와 함께 넘기고, 실제로 센 행 가운데 FAILED 가 된 행만 돌려준다") {
                counted.captured shouldContainExactly listOf(
                    PublishFailure(first, "broker ack timed out"),
                    PublishFailure(second, "E".repeat(255)),
                    PublishFailure(third, "broker ack timed out"),
                )
                recorded shouldContainExactly listOf(exhausted)
            }
        }
    }

    Given("모두 이미 PUBLISHED 나 FAILED 가 된 행의 발행 실패") {
        val repository = mockk<OutboxRepository>()
        val service = OutboxService(repository)
        every { repository.countFailures(OutboxSource.POINT, any(), OutboxRelayPolicy.MAX_PUBLISH_ATTEMPTS) } returns intArrayOf(0)

        When("실패를 기록하면") {
            val recorded = service.recordFailures(OutboxSource.POINT, listOf(PublishFailure(message(4), "timeout")))

            Then("센 행이 없어 FAILED 를 다시 읽지 않고 알림도 두 번 나지 않는다") {
                recorded.shouldBeEmpty()
                verify(exactly = 0) { repository.findFailed(any(), any()) }
            }
        }
    }

    Given("집을 행이 없는 스키마와 두 행이 있는 스키마") {
        val repository = mockk<OutboxRepository>()
        val service = OutboxService(repository)
        every { repository.findClaimable(OutboxSource.ORDER, OutboxRelayPolicy.BATCH_SIZE, OutboxRelayPolicy.CLAIM_LEASE_SECONDS) } returns emptyList()
        every { repository.findClaimable(OutboxSource.PAYMENT, OutboxRelayPolicy.BATCH_SIZE, OutboxRelayPolicy.CLAIM_LEASE_SECONDS) } returns
            listOf(message(5), message(6))
        every { repository.markClaimed(OutboxSource.PAYMENT, listOf(5L, 6L)) } returns 2

        When("집으면") {
            val empty = service.claim(OutboxSource.ORDER)
            val claimed = service.claim(OutboxSource.PAYMENT)

            Then("집은 행에만 임대를 건다") {
                empty.shouldBeEmpty()
                claimed.map { it.id } shouldContainExactly listOf(5L, 6L)
                verify(exactly = 0) { repository.markClaimed(OutboxSource.ORDER, any()) }
                verify(exactly = 1) { repository.markClaimed(OutboxSource.PAYMENT, listOf(5L, 6L)) }
            }
        }
    }

    Given("표시·정리·관측 요청") {
        val repository = mockk<OutboxRepository>()
        val service = OutboxService(repository)
        every { repository.markPublished(OutboxSource.PRODUCT, listOf(7L)) } returns 1
        every { repository.deletePublishedBefore(OutboxSource.PRODUCT, PublishedOutboxRetentionPolicy.RETENTION_DAYS, PublishedOutboxRetentionPolicy.CLEANUP_CHUNK) } returns 3
        every { repository.findDelay(OutboxSource.PRODUCT) } returns OutboxDelay(0, null, 1)

        When("각각 부르면") {
            val published = service.markPublished(OutboxSource.PRODUCT, listOf(7L))
            val cleaned = service.cleanUpChunk(OutboxSource.PRODUCT)
            val delay = service.delayOf(OutboxSource.PRODUCT)

            Then("보존 기간·묶음 크기 정책으로 저장소에 넘긴다") {
                published shouldBe 1
                cleaned shouldBe 3
                delay.failed shouldBe 1L
            }
        }
    }
})
