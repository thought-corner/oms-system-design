package com.project.order.service.worker

import com.project.order.client.AlertSender
import com.project.order.client.RemoteCallPolicy
import com.project.order.domain.SagaStatus
import com.project.order.service.OrderSagaStateService
import com.project.order.service.SagaOrchestrator
import com.project.order.service.policy.SagaRecoveryPolicy
import com.project.order.service.dto.SagaContext
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class CompensationWorker(
    private val sagaState: OrderSagaStateService,
    private val sagaOrchestrator: SagaOrchestrator,
    private val alertSender: AlertSender,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelay = SagaRecoveryPolicy.SWEEP_INTERVAL_MS)
    fun sweep() {
        val candidates = sagaState.findStuck()
        if (candidates.isEmpty()) {
            return
        }

        log.info("Saga recovery started: count={}", candidates.size)
        candidates.forEach { recover(it) }
    }

    private fun recover(sagaId: String) {
        try {
            val stuck = sagaState.claim(sagaId) ?: return
            val context = sagaState.contextOf(stuck.sagaId)

            when {
                stuck.status == SagaStatus.RUNNING && stuck.paymentDone -> complete(stuck)
                stuck.status == SagaStatus.RUNNING -> resume(stuck, context)
                else -> sagaOrchestrator.compensate(context, "recovered from ${stuck.status}", RemoteCallPolicy.COMPENSATION_WORKER_ATTEMPTS)
            }
        } catch (e: RuntimeException) {
            log.error("Saga recovery failed: sagaId={}", sagaId, e)
        }
    }

    private fun complete(stuck: OrderSagaStateService.StuckSaga) {
        try {
            sagaState.succeed(stuck.sagaId, stuck.orderId)
            log.info("Saga completed without replay: sagaId={}, orderId={}", stuck.sagaId, stuck.orderId)
        } catch (e: RuntimeException) {
            log.warn("Saga completion failed, will retry: sagaId={}, orderId={}, cause={}", stuck.sagaId, stuck.orderId, e.message)
            val alert = sagaState.forwardRecoveryFailed(stuck.sagaId, e.message) ?: return
            if (alert.attempts == SagaRecoveryPolicy.FORWARD_RECOVERY_ALERT_ATTEMPTS) {
                alertSender.send(alert)
            }
        }
    }

    private fun resume(stuck: OrderSagaStateService.StuckSaga, context: SagaContext) {
        try {
            sagaOrchestrator.run(context)
            log.info("Saga resumed and completed: sagaId={}, orderId={}", stuck.sagaId, stuck.orderId)
        } catch (e: RuntimeException) {
            log.info("Saga resume failed: sagaId={}, orderId={}, cause={}", stuck.sagaId, stuck.orderId, e.message)
        }
    }
}
