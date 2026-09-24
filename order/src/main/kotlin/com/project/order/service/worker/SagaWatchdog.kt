package com.project.order.service.worker

import com.project.order.client.AlertSender
import com.project.order.service.SagaRecoveryService
import com.project.order.service.dto.StuckSaga
import com.project.order.service.policy.SagaRecoveryPolicy
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class SagaWatchdog(
    private val recoveryService: SagaRecoveryService,
    private val alertSender: AlertSender,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelay = SagaRecoveryPolicy.SWEEP_INTERVAL_MS)
    fun sweep() {
        val candidates = recoveryService.findStuck()
        if (candidates.isEmpty()) {
            return
        }

        log.info("Saga watchdog started: count={}", candidates.size)
        candidates.forEach { recover(it) }
    }

    private fun recover(stuck: StuckSaga) {
        try {
            recoveryService.recover(stuck)?.let { alertSender.send(it) }
        } catch (e: RuntimeException) {
            log.error("Saga recovery failed: sagaId={}, orderId={}", stuck.sagaId, stuck.orderId, e)
        }
    }
}
