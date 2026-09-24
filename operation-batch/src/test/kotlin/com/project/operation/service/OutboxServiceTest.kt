package com.project.operation.service

import com.project.operation.domain.OutboxBacklog
import com.project.operation.domain.OutboxFailure
import com.project.operation.domain.OutboxSource
import com.project.operation.domain.OutboxStatus
import com.project.operation.domain.PublishFailure
import com.project.operation.fixture.OutboxFixture.message
import com.project.operation.repository.OutboxRepository
import com.project.operation.service.policy.OutboxRelayPolicy
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify

class OutboxServiceTest : BehaviorSpec({

    Given("실패가 0번인 행, 4번인 행, 그새 PENDING 이 아니게 된 행의 발행 실패") {
        val repository = mockk<OutboxRepository>()
        val service = OutboxService(repository)
        val longError = "E".repeat(300)
        every { repository.findPendingFailCountsForUpdate(OutboxSource.ORDER, listOf(1L, 2L, 3L)) } returns mapOf(1L to 0, 2L to 4)
        every { repository.recordFailures(OutboxSource.ORDER, any()) } returns intArrayOf(1, 1)

        When("실패를 기록하면") {
            val recorded = service.recordFailures(
                OutboxSource.ORDER,
                listOf(
                    PublishFailure(message(1), "broker ack timed out"),
                    PublishFailure(message(2), longError),
                    PublishFailure(message(3), "broker ack timed out"),
                ),
            )

            Then("fail_count 를 하나 올리고 5번째 실패만 FAILED 로 바꾸며 사유는 255자로 자른다") {
                val expected = listOf(
                    OutboxFailure(message(1), 1, OutboxStatus.PENDING, "broker ack timed out"),
                    OutboxFailure(message(2), OutboxRelayPolicy.MAX_PUBLISH_ATTEMPTS, OutboxStatus.FAILED, "E".repeat(255)),
                )
                recorded shouldContainExactly expected
                verify(exactly = 1) { repository.recordFailures(OutboxSource.ORDER, expected) }
            }
        }
    }

    Given("모두 이미 PUBLISHED 나 FAILED 가 된 행의 발행 실패") {
        val repository = mockk<OutboxRepository>()
        val service = OutboxService(repository)
        every { repository.findPendingFailCountsForUpdate(OutboxSource.POINT, listOf(4L)) } returns emptyMap()

        When("실패를 기록하면") {
            val recorded = service.recordFailures(OutboxSource.POINT, listOf(PublishFailure(message(4), "timeout")))

            Then("아무 행도 바꾸지 않아 FAILED 전이와 알림이 두 번 나지 않는다") {
                recorded.shouldBeEmpty()
                verify(exactly = 0) { repository.recordFailures(any(), any()) }
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
        every { repository.deletePublishedBefore(OutboxSource.PRODUCT, OutboxRelayPolicy.RETENTION_DAYS, OutboxRelayPolicy.PURGE_CHUNK) } returns 3
        every { repository.findBacklog(OutboxSource.PRODUCT) } returns OutboxBacklog(0, null, 1)

        When("각각 부르면") {
            val published = service.markPublished(OutboxSource.PRODUCT, listOf(7L))
            val purged = service.purgeChunk(OutboxSource.PRODUCT)
            val backlog = service.backlogOf(OutboxSource.PRODUCT)

            Then("보존 기간·묶음 크기 정책으로 저장소에 넘긴다") {
                published shouldBe 1
                purged shouldBe 3
                backlog.failed shouldBe 1L
            }
        }
    }
})
