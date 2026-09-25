package com.project.operation.service

import com.project.operation.domain.OutboxBacklog
import com.project.operation.domain.OutboxFailure
import com.project.operation.domain.OutboxMessage
import com.project.operation.domain.OutboxSource
import com.project.operation.domain.OutboxStatus
import com.project.operation.domain.PublishFailure
import com.project.operation.repository.OutboxRepository
import com.project.operation.service.policy.OutboxRelayPolicy
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional

@Service
class OutboxService(
    private val outboxRepository: OutboxRepository,
) {

    @Transactional(isolation = Isolation.READ_COMMITTED)
    fun claim(source: OutboxSource): List<OutboxMessage> {
        val claimed = outboxRepository.findClaimable(source, OutboxRelayPolicy.BATCH_SIZE, OutboxRelayPolicy.CLAIM_LEASE_SECONDS)
        if (claimed.isNotEmpty()) {
            outboxRepository.markClaimed(source, claimed.map { it.id })
        }
        return claimed
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    fun markPublished(source: OutboxSource, ids: Collection<Long>): Int =
        outboxRepository.markPublished(source, ids)

    @Transactional(isolation = Isolation.READ_COMMITTED)
    fun recordFailures(source: OutboxSource, failures: List<PublishFailure>): List<OutboxFailure> {
        val failCounts = outboxRepository.findPendingFailCountsForUpdate(source, failures.map { it.message.id })
        val recorded = failures.mapNotNull { failure ->
            failCounts[failure.message.id]?.let { previous -> nextFailure(failure, previous + 1) }
        }
        if (recorded.isNotEmpty()) {
            outboxRepository.recordFailures(source, recorded)
        }
        return recorded
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    fun purgeChunk(source: OutboxSource): Int =
        outboxRepository.deletePublishedBefore(source, OutboxRelayPolicy.RETENTION_DAYS, OutboxRelayPolicy.PURGE_CHUNK)

    @Transactional(isolation = Isolation.READ_COMMITTED, readOnly = true)
    fun backlogOf(source: OutboxSource): OutboxBacklog =
        outboxRepository.findBacklog(source)

    private fun nextFailure(failure: PublishFailure, failCount: Int) = OutboxFailure(
        message = failure.message,
        failCount = failCount,
        status = if (failCount >= OutboxRelayPolicy.MAX_PUBLISH_ATTEMPTS) OutboxStatus.FAILED else OutboxStatus.PENDING,
        lastError = failure.error.take(OutboxRelayPolicy.LAST_ERROR_MAX_LENGTH),
    )
}
