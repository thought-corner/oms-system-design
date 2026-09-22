package com.project.order.service

import com.project.order.domain.OrderEvent
import com.project.order.domain.OrderSaga
import com.project.order.domain.OrderStatus
import com.project.order.domain.SagaEvent
import com.project.order.domain.SagaStatus
import com.project.common.exception.BusinessException
import com.project.order.exception.OrderErrorCode
import com.project.order.client.CompensationFailedAlert
import com.project.order.repository.OrderItemRepository
import com.project.order.repository.OrderRepository
import com.project.order.repository.OrderSagaRepository
import com.project.order.service.dto.SagaContext
import com.project.order.service.policy.SagaRecoveryPolicy
import org.springframework.data.domain.Limit
import com.project.order.statemachine.OrderStateMachine
import com.project.order.statemachine.SagaStateMachine
import org.slf4j.LoggerFactory
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

        val next = orderStateMachine.transition(orderId, order.status, OrderEvent.PLACE)
        apply(orderId, order.status, next) { order.transitionTo(next, now()) }

        val saga = orderSagaRepository.save(
            OrderSaga(sagaId = UUID.randomUUID().toString(), orderId = orderId, createdAt = now()),
        )

        return SagaContext(
            sagaId = saga.sagaId,
            orderId = orderId,
            userId = order.userId,
            items = orderItemRepository.findAllByOrderId(orderId)
                .sortedBy { it.productId }
                .map { SagaContext.Item(it.productId, it.quantity) },
        )
    }

    @Transactional(readOnly = true)
    fun contextOf(sagaId: String): SagaContext {
        val saga = saga(sagaId)
        val order = orderRepository.findById(saga.orderId).orElseThrow {
            BusinessException(OrderErrorCode.ORDER_NOT_FOUND, "orderId=${saga.orderId}")
        }

        return SagaContext(
            sagaId = saga.sagaId,
            orderId = saga.orderId,
            userId = order.userId,
            items = orderItemRepository.findAllByOrderId(saga.orderId)
                .sortedBy { it.productId }
                .map { SagaContext.Item(it.productId, it.quantity) },
        )
    }

    @Transactional
    fun findStuck(): List<StuckSaga> =
        orderSagaRepository.findByStatusInAndUpdatedAtLessThanOrderByUpdatedAtAsc(
            RECOVERABLE,
            now().minus(SagaRecoveryPolicy.STUCK_THRESHOLD),
            Limit.of(SagaRecoveryPolicy.BATCH_SIZE),
        ).map { StuckSaga(it.sagaId, it.orderId, it.status) }

    @Transactional
    fun stockCompleted(sagaId: String, totalPrice: Long) = saga(sagaId).stockCompleted(totalPrice, now())

    @Transactional
    fun pointCompleted(sagaId: String) = saga(sagaId).pointCompleted(now())

    @Transactional
    fun paymentCompleted(sagaId: String) = saga(sagaId).paymentCompleted(now())

    @Transactional
    fun succeed(sagaId: String, orderId: Long) {
        val order = orderRepository.findWithLockById(orderId)
            ?: throw BusinessException(OrderErrorCode.ORDER_NOT_FOUND, "orderId=$orderId")

        val next = orderStateMachine.transition(orderId, order.status, OrderEvent.COMPLETE)
        apply(orderId, order.status, next) { order.transitionTo(next, now()) }
        advance(saga(sagaId), SagaEvent.COMPLETE)
    }

    @Transactional
    fun beginCompensation(sagaId: String, error: String?) {
        val saga = saga(sagaId)
        saga.recordError(error)

        if (saga.status != SagaStatus.COMPENSATING) {
            advance(saga, SagaEvent.COMPENSATE)
        }
    }

    @Transactional
    fun compensated(sagaId: String, orderId: Long) {
        val order = orderRepository.findWithLockById(orderId)
            ?: throw BusinessException(OrderErrorCode.ORDER_NOT_FOUND, "orderId=$orderId")

        val next = orderStateMachine.transition(orderId, order.status, OrderEvent.FAIL)
        apply(orderId, order.status, next) { order.transitionTo(next, now()) }
        advance(saga(sagaId), SagaEvent.COMPENSATION_DONE)
    }

    @Transactional
    fun compensationFailed(sagaId: String, error: String?): CompensationFailedAlert {
        val saga = saga(sagaId)
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

    private fun saga(sagaId: String): OrderSaga =
        orderSagaRepository.findBySagaId(sagaId)
            ?: throw BusinessException(OrderErrorCode.ORDER_NOT_FOUND, "sagaId=$sagaId")

    private fun advance(saga: OrderSaga, event: SagaEvent) {
        val previous = saga.status
        val next: SagaStatus = sagaStateMachine.transition(saga.sagaId, previous, event)
        saga.transitionTo(next, now())
        log.info("Saga state transition applied: sagaId={}, {} -> {}", saga.sagaId, previous, next)
    }

    private fun apply(orderId: Long, previous: OrderStatus, next: OrderStatus, block: () -> Unit) {
        block()
        log.info("Order state transition applied: orderId={}, {} -> {}", orderId, previous, next)
    }

    private fun now(): LocalDateTime = LocalDateTime.now(clock)

    data class StuckSaga(
        val sagaId: String,
        val orderId: Long,
        val status: SagaStatus,
    )

    companion object {
        private val RECOVERABLE = listOf(
            SagaStatus.RUNNING,
            SagaStatus.COMPENSATING,
            SagaStatus.COMPENSATION_FAILED,
        )
    }

}
