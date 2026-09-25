package com.project.operation.service.worker

import com.project.operation.domain.OutboxSource
import com.project.operation.service.OutboxService
import com.project.operation.service.policy.PublishedOutboxRetentionPolicy
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class PublishedOutboxCleaner(
    private val outboxService: OutboxService,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = PublishedOutboxRetentionPolicy.CLEANUP_CRON, zone = PublishedOutboxRetentionPolicy.CLEANUP_ZONE)
    fun cleanUp() {
        OutboxSource.entries.forEach { cleanUp(it) }
    }

    private fun cleanUp(source: OutboxSource) {
        try {
            var total = 0
            do {
                val deleted = outboxService.cleanUpChunk(source)
                total += deleted
            } while (deleted == PublishedOutboxRetentionPolicy.CLEANUP_CHUNK)
            log.info("발행된 outbox 행을 정리했습니다. schema={}, deleted={}", source.schema, total)
        } catch (e: RuntimeException) {
            log.warn("outbox 정리에 실패했습니다. schema={}, cause={}", source.schema, e.message)
        }
    }
}
