package com.project.operation.service

import com.project.operation.domain.OutboxDelay
import com.project.operation.domain.OutboxFailure
import com.project.operation.domain.OutboxMessage
import com.project.operation.domain.OutboxSource
import com.project.operation.domain.PublishFailure
import com.project.operation.repository.OutboxRepository
import com.project.operation.service.policy.OutboxRelayPolicy
import com.project.operation.service.policy.PublishedOutboxRetentionPolicy
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class OutboxService(
    private val outboxRepository: OutboxRepository,
) {

    @Transactional
    fun claim(source: OutboxSource): List<OutboxMessage> {
        val claimed = outboxRepository.findClaimable(source, OutboxRelayPolicy.BATCH_SIZE, OutboxRelayPolicy.CLAIM_LEASE_SECONDS)
        if (claimed.isNotEmpty()) {
            outboxRepository.markClaimed(source, claimed.map { it.id })
        }
        return claimed
    }

    @Transactional
    fun markPublished(source: OutboxSource, ids: Collection<Long>): Int =
        outboxRepository.markPublished(source, ids)

    @Transactional
    fun recordFailures(source: OutboxSource, failures: List<PublishFailure>): List<OutboxFailure> {
        val affectedRows = outboxRepository.countFailures(
            source,
            failures.map { PublishFailure(it.message, it.error.take(OutboxRelayPolicy.LAST_ERROR_MAX_LENGTH)) },
            OutboxRelayPolicy.MAX_PUBLISH_ATTEMPTS,
        )
        val countedMessages = failures.filterIndexed { index, _ -> affectedRows[index] > 0 }.map { it.message }
        if (countedMessages.isEmpty()) {
            return emptyList()
        }
        return outboxRepository.findFailed(source, countedMessages)
    }

    @Transactional
    fun cleanUpChunk(source: OutboxSource): Int =
        outboxRepository.deletePublishedBefore(source, PublishedOutboxRetentionPolicy.RETENTION_DAYS, PublishedOutboxRetentionPolicy.CLEANUP_CHUNK)

    @Transactional(readOnly = true)
    fun delayOf(source: OutboxSource): OutboxDelay =
        outboxRepository.findDelay(source)
}
