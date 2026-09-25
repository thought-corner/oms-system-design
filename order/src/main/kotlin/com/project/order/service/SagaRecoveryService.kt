package com.project.order.service

import com.project.common.exception.BusinessException
import com.project.order.client.CompensationFailedAlert
import com.project.order.client.ForwardRecoveryFailedAlert
import com.project.order.client.OutboxStalledAlert
import com.project.order.client.SagaAlert
import com.project.order.domain.Order
import com.project.order.domain.OrderSaga
import com.project.order.domain.OutboxMessage
import com.project.order.domain.OutboxStatus
import com.project.order.domain.SagaEvent
import com.project.order.domain.SagaStatus
import com.project.order.repository.OrderRepository
import com.project.order.repository.OrderSagaRepository
import com.project.order.service.dto.StuckSaga
import com.project.order.service.policy.SagaRecoveryPolicy
import org.slf4j.LoggerFactory
import org.springframework.data.domain.Limit
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDateTime

@Service
class SagaRecoveryService(
    private val orderRepository: OrderRepository,
    private val orderSagaRepository: OrderSagaRepository,
    private val sagaProgress: SagaProgress,
    private val commandOutbox: SagaCommandOutbox,
    private val sagaCompensation: SagaCompensation,
    private val clock: Clock,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional(readOnly = true)
    fun findStuck(): List<StuckSaga> =
        orderSagaRepository.findByStatusInAndUpdatedAtLessThanOrderByUpdatedAtAsc(
            RECOVERABLE,
            stuckThreshold(),
            Limit.of(SagaRecoveryPolicy.BATCH_SIZE),
        ).map { StuckSaga(it.sagaId, it.orderId) }

    @Transactional
    fun recover(stuck: StuckSaga): SagaAlert? {
        val order = orderRepository.findWithWaitingLockById(stuck.orderId) ?: return null
        val saga = orderSagaRepository.findWithLockBySagaIdAndStatusInAndUpdatedAtLessThan(
            stuck.sagaId,
            RECOVERABLE,
            stuckThreshold(),
        ) ?: return null

        saga.claim(now())

        val forward = sagaProgress.accepts(saga, SagaEvent.PROCEED)
        val awaited = commandOutbox.awaitingRelay(saga, forward)
        if (awaited.isNotEmpty()) {
            return stalled(saga, awaited)
        }

        if (forward) {
            return if (saga.paymentDone) completeForward(order, saga) else reissueForward(order, saga)
        }

        return resumeCompensation(order, saga)
    }

    private fun stalled(saga: OrderSaga, awaited: List<OutboxMessage>): SagaAlert? {
        log.info("Saga recovery waits for outbox relay: sagaId={}, unpublished={}", saga.sagaId, awaited.size)

        val failed = awaited.filter { it.status == OutboxStatus.FAILED }
        val oldest = failed.firstOrNull()
            ?: awaited.first().takeIf { it.occurredAt.isBefore(now().minus(SagaRecoveryPolicy.OUTBOX_STALLED_AGE)) }
            ?: return null

        return OutboxStalledAlert(
            sagaId = saga.sagaId,
            orderId = saga.orderId,
            messageType = oldest.messageType,
            status = oldest.status.name,
            occurredAt = oldest.occurredAt,
            failedCount = failed.size,
        )
    }

    private fun completeForward(order: Order, saga: OrderSaga): SagaAlert? =
        try {
            sagaProgress.complete(order, saga)
            log.info("Saga completed without re-publish: sagaId={}, orderId={}", saga.sagaId, saga.orderId)
            null
        } catch (e: BusinessException) {
            log.warn("Saga completion failed, will retry: sagaId={}, orderId={}, cause={}", saga.sagaId, saga.orderId, e.message)
            saga.recordError(e.message)
            countForwardAttempt(saga)
        }

    private fun reissueForward(order: Order, saga: OrderSaga): SagaAlert? {
        commandOutbox.appendForward(saga, order)
        log.info("Saga forward command re-published: sagaId={}, step={}", saga.sagaId, saga.currentStep)
        saga.recordError("no reply for ${saga.currentStep}")
        return countForwardAttempt(saga)
    }

    private fun countForwardAttempt(saga: OrderSaga): SagaAlert? {
        saga.countAttempt()
        if (saga.attempts != SagaRecoveryPolicy.FORWARD_RECOVERY_ALERT_ATTEMPTS) {
            return null
        }

        return ForwardRecoveryFailedAlert(
            sagaId = saga.sagaId,
            orderId = saga.orderId,
            step = saga.currentStep.name,
            attempts = saga.attempts,
            lastError = saga.lastError,
        )
    }

    private fun resumeCompensation(order: Order, saga: OrderSaga): SagaAlert? {
        if (saga.allCanceled) {
            sagaProgress.close(order, saga)
            log.info("Saga compensation closed without re-publish: sagaId={}, orderId={}", saga.sagaId, saga.orderId)
            return null
        }

        val reissued = sagaCompensation.resume(saga)
        log.info("Saga cancel commands re-published: sagaId={}, commands={}", saga.sagaId, reissued)
        saga.countAttempt()

        if (saga.attempts < SagaRecoveryPolicy.COMPENSATION_ALERT_ATTEMPTS) {
            return null
        }

        sagaProgress.advance(saga, SagaEvent.COMPENSATION_FAIL)
        if (saga.attempts != SagaRecoveryPolicy.COMPENSATION_ALERT_ATTEMPTS) {
            return null
        }

        return CompensationFailedAlert(
            sagaId = saga.sagaId,
            orderId = saga.orderId,
            attempts = saga.attempts,
            lastError = saga.lastError,
            pendingCancels = saga.pendingCancels.map { it.name },
        )
    }

    private fun now(): LocalDateTime = LocalDateTime.now(clock)

    private fun stuckThreshold(): LocalDateTime = now().minus(SagaRecoveryPolicy.STUCK_THRESHOLD)

    companion object {
        private val RECOVERABLE = listOf(
            SagaStatus.RUNNING,
            SagaStatus.COMPENSATING,
            SagaStatus.COMPENSATION_FAILED,
        )
    }
}
