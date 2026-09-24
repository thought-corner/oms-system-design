package com.project.order.service

import com.project.common.exception.BusinessException
import com.project.order.client.CompensationFailedAlert
import com.project.order.client.ForwardRecoveryFailedAlert
import com.project.order.domain.Order
import com.project.order.domain.OrderEvent
import com.project.order.domain.OrderSaga
import com.project.order.domain.SagaEvent
import com.project.order.domain.SagaStatus
import com.project.order.exception.OrderErrorCode
import com.project.order.repository.OrderItemRepository
import com.project.order.repository.OrderRepository
import com.project.order.repository.OrderSagaRepository
import com.project.order.service.dto.SagaContext
import com.project.order.service.policy.SagaRecoveryPolicy
import com.project.order.statemachine.OrderStateMachine
import com.project.order.statemachine.SagaStateMachine
import org.slf4j.LoggerFactory
import org.springframework.data.domain.Limit
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDateTime
import java.util.UUID

@Service
class OrderSagaStateService(
    private val orderRepository: OrderRepository,
    private val orderItemRepository: OrderItemRepository,
    private val orderSagaRepository: OrderSagaRepository,
    private val orderStateMachine: OrderStateMachine,
    private val sagaStateMachine: SagaStateMachine,
    private val clock: Clock,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun start(orderId: Long): SagaContext? {
        val order = orderRepository.findWithLockById(orderId)
            ?: throw BusinessException(OrderErrorCode.ORDER_NOT_FOUND, "orderId=$orderId")

        if (order.isCompleted) {
            return null
        }

        advance(order, OrderEvent.PLACE)

        val saga = orderSagaRepository.save(
            OrderSaga(sagaId = UUID.randomUUID().toString(), orderId = orderId, createdAt = now()),
        )

        return sagaContext(saga, order)
    }

    @Transactional(readOnly = true)
    fun contextOf(sagaId: String): SagaContext {
        val saga = saga(sagaId)
        val order = orderRepository.findById(saga.orderId).orElseThrow {
            BusinessException(OrderErrorCode.ORDER_NOT_FOUND, "orderId=${saga.orderId}")
        }

        return sagaContext(saga, order)
    }

    @Transactional(readOnly = true)
    fun findStuck(): List<String> =
        orderSagaRepository.findByStatusInAndUpdatedAtLessThanOrderByUpdatedAtAsc(
            RECOVERABLE,
            stuckThreshold(),
            Limit.of(SagaRecoveryPolicy.BATCH_SIZE),
        ).map { it.sagaId }

    @Transactional
    fun claim(sagaId: String): StuckSaga? {
        val saga = orderSagaRepository.findWithLockBySagaIdAndStatusInAndUpdatedAtLessThan(
            sagaId,
            RECOVERABLE,
            stuckThreshold(),
        ) ?: return null

        saga.claim(now())
        return StuckSaga(saga.sagaId, saga.orderId, saga.status, saga.paymentDone)
    }

    @Transactional
    fun stockCompleted(sagaId: String, totalPrice: Long) = lockedSaga(sagaId).stockCompleted(totalPrice, now())

    @Transactional
    fun pointCompleted(sagaId: String) = lockedSaga(sagaId).pointCompleted(now())

    @Transactional
    fun paymentCompleted(sagaId: String) = lockedSaga(sagaId).paymentCompleted(now())

    @Transactional
    fun succeed(sagaId: String, orderId: Long) {
        advance(lockedOrder(orderId), OrderEvent.COMPLETE)
        advance(lockedSaga(sagaId), SagaEvent.COMPLETE)
    }

    @Transactional
    fun beginCompensation(sagaId: String, error: String?) {
        val saga = lockedSaga(sagaId)
        saga.recordError(error)
        advance(saga, SagaEvent.COMPENSATE)
    }

    @Transactional
    fun compensated(sagaId: String, orderId: Long) {
        advance(lockedOrder(orderId), OrderEvent.FAIL)
        advance(lockedSaga(sagaId), SagaEvent.COMPENSATION_DONE)
    }

    @Transactional
    fun compensationFailed(sagaId: String, error: String?): CompensationFailedAlert {
        val saga = lockedSaga(sagaId)
        saga.recordError(error)
        saga.countAttempt()
        advance(saga, SagaEvent.COMPENSATION_FAIL)

        return CompensationFailedAlert(
            sagaId = saga.sagaId,
            orderId = saga.orderId,
            attempts = saga.attempts,
            lastError = saga.lastError,
            stockDone = saga.stockDone,
            pointDone = saga.pointDone,
            paymentDone = saga.paymentDone,
        )
    }

    @Transactional
    fun forwardRecoveryFailed(sagaId: String, error: String?): ForwardRecoveryFailedAlert? {
        val saga = lockedSaga(sagaId)
        if (saga.isSucceeded) {
            return null
        }

        saga.recordError(error)
        saga.countAttempt()

        return ForwardRecoveryFailedAlert(
            sagaId = saga.sagaId,
            orderId = saga.orderId,
            attempts = saga.attempts,
            lastError = saga.lastError,
        )
    }

    private fun lockedOrder(orderId: Long): Order =
        orderRepository.findWithWaitingLockById(orderId)
            ?: throw BusinessException(OrderErrorCode.ORDER_NOT_FOUND, "orderId=$orderId")

    private fun saga(sagaId: String): OrderSaga =
        orderSagaRepository.findBySagaId(sagaId)
            ?: throw BusinessException(OrderErrorCode.ORDER_NOT_FOUND, "sagaId=$sagaId")

    private fun lockedSaga(sagaId: String): OrderSaga =
        orderSagaRepository.findWithWaitingLockBySagaId(sagaId)
            ?: throw BusinessException(OrderErrorCode.ORDER_NOT_FOUND, "sagaId=$sagaId")

    private fun advance(saga: OrderSaga, event: SagaEvent) {
        val previous = saga.status
        val next: SagaStatus = sagaStateMachine.transition(saga.sagaId, previous, event)
        saga.transitionTo(next, now())
        log.info("Saga state transition applied: sagaId={}, {} -> {}", saga.sagaId, previous, next)
    }

    private fun advance(order: Order, event: OrderEvent) {
        val orderId = requireNotNull(order.id)
        val previous = order.status
        val next = orderStateMachine.transition(orderId, previous, event)
        order.transitionTo(next, now())
        log.info("Order state transition applied: orderId={}, {} -> {}", orderId, previous, next)
    }

    private fun sagaContext(saga: OrderSaga, order: Order): SagaContext =
        SagaContext(
            sagaId = saga.sagaId,
            orderId = saga.orderId,
            userId = order.userId,
            items = orderItemRepository.findAllByOrderId(saga.orderId)
                .sortedBy { it.productId }
                .map { SagaContext.Item(it.productId, it.quantity) },
        )

    private fun now(): LocalDateTime = LocalDateTime.now(clock)

    private fun stuckThreshold(): LocalDateTime = now().minus(SagaRecoveryPolicy.STUCK_THRESHOLD)

    data class StuckSaga(
        val sagaId: String,
        val orderId: Long,
        val status: SagaStatus,
        val paymentDone: Boolean,
    )

    companion object {
        private val RECOVERABLE = listOf(
            SagaStatus.RUNNING,
            SagaStatus.COMPENSATING,
            SagaStatus.COMPENSATION_FAILED,
        )
    }

}
