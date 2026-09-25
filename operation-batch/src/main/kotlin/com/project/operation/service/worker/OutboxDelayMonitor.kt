package com.project.operation.service.worker

import com.project.operation.client.AlertSender
import com.project.operation.client.OutboxDelayAlert
import com.project.operation.domain.OutboxSource
import com.project.operation.service.OutboxService
import com.project.operation.service.policy.OutboxDelayPolicy
import java.time.Clock
import java.time.Duration
import java.time.LocalDateTime
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class OutboxDelayMonitor(
    private val outboxService: OutboxService,
    private val alertSender: AlertSender,
    private val clock: Clock,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelay = OutboxDelayPolicy.DELAY_CHECK_INTERVAL_MS)
    fun checkDelays() {
        OutboxSource.entries.forEach { checkDelays(it) }
    }

    private fun checkDelays(source: OutboxSource) {
        try {
            val delay = outboxService.delayOf(source)
            if (delay.failed > 0) {
                log.warn("outbox에 운영자의 조치를 기다리는 FAILED 행이 있습니다. schema={}, failed={}", source.schema, delay.failed)
            }
            val oldest = delay.oldestPendingOccurredAt ?: return
            val age = Duration.between(oldest, LocalDateTime.now(clock))
            if (age > OutboxDelayPolicy.DELAY_ALERT_AGE) {
                alertSender.send(OutboxDelayAlert(source.schema, delay.pending, oldest, age.seconds, delay.failed))
            }
        } catch (e: RuntimeException) {
            log.warn("outbox 적체 확인에 실패했습니다. schema={}, cause={}", source.schema, e.message)
        }
    }
}
