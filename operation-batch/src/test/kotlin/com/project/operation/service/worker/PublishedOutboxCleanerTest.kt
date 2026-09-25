package com.project.operation.service.worker

import com.project.operation.domain.OutboxSource
import com.project.operation.service.OutboxService
import com.project.operation.service.policy.PublishedOutboxRetentionPolicy
import io.kotest.core.spec.style.BehaviorSpec
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.springframework.dao.DataAccessResourceFailureException

class PublishedOutboxCleanerTest : BehaviorSpec({

    Given("발행한 지 7일 지난 행이 한 묶음보다 많은 스키마와 정리가 실패하는 스키마") {
        val outboxService: OutboxService = mockk()
        val cleaner = PublishedOutboxCleaner(outboxService)
        every { outboxService.cleanUpChunk(OutboxSource.ORDER) } returnsMany listOf(PublishedOutboxRetentionPolicy.CLEANUP_CHUNK, 3)
        every { outboxService.cleanUpChunk(OutboxSource.PRODUCT) } throws DataAccessResourceFailureException("DELETE command denied")
        every { outboxService.cleanUpChunk(OutboxSource.POINT) } returns 0
        every { outboxService.cleanUpChunk(OutboxSource.PAYMENT) } returns 0

        When("정리가 돌면") {
            cleaner.cleanUp()

            Then("묶음이 가득 찬 동안 반복하고 실패한 스키마가 나머지를 막지 않는다") {
                verify(exactly = 2) { outboxService.cleanUpChunk(OutboxSource.ORDER) }
                verify(exactly = 1) { outboxService.cleanUpChunk(OutboxSource.POINT) }
                verify(exactly = 1) { outboxService.cleanUpChunk(OutboxSource.PAYMENT) }
            }
        }
    }
})
