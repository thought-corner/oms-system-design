package com.project.order.service

import com.project.order.domain.Order
import com.project.order.domain.OrderEvent
import com.project.order.domain.OrderSaga
import com.project.order.domain.SagaEvent
import com.project.order.statemachine.OrderStateMachine
import com.project.order.statemachine.SagaStateMachine
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.LocalDateTime

@Component
class SagaProgress(
    private val orderStateMachine: OrderStateMachine,
    private val sagaStateMachine: SagaStateMachine,
    private val clock: Clock,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun accepts(saga: OrderSaga, event: SagaEvent): Boolean = sagaStateMachine.accepts(saga.sagaId, saga.status, event)

    fun complete(order: Order, saga: OrderSaga) {
        apply(order, saga, OrderEvent.COMPLETE, SagaEvent.COMPLETE)
    }

    fun close(order: Order, saga: OrderSaga) {
        apply(order, saga, OrderEvent.FAIL, SagaEvent.COMPENSATION_DONE)
    }

    fun advance(saga: OrderSaga, event: SagaEvent) {
        val previous = saga.status
        val next = sagaStateMachine.transition(saga.sagaId, previous, event)
        saga.transitionTo(next, now())
        log.info("Saga state transition applied: sagaId={}, {} -> {}", saga.sagaId, previous, next)
    }

    fun advance(order: Order, event: OrderEvent) {
        val orderId = requireNotNull(order.id)
        val previous = order.status
        val next = orderStateMachine.transition(orderId, previous, event)
        order.transitionTo(next, now())
        log.info("Order state transition applied: orderId={}, {} -> {}", orderId, previous, next)
    }

    private fun apply(order: Order, saga: OrderSaga, orderEvent: OrderEvent, sagaEvent: SagaEvent) {
        val orderId = requireNotNull(order.id)
        val orderPrevious = order.status
        val sagaPrevious = saga.status
        val orderNext = orderStateMachine.transition(orderId, orderPrevious, orderEvent)
        val sagaNext = sagaStateMachine.transition(saga.sagaId, sagaPrevious, sagaEvent)

        val at = now()
        order.transitionTo(orderNext, at)
        saga.transitionTo(sagaNext, at)
        log.info("Order state transition applied: orderId={}, {} -> {}", orderId, orderPrevious, orderNext)
        log.info("Saga state transition applied: sagaId={}, {} -> {}", saga.sagaId, sagaPrevious, sagaNext)
    }

    private fun now(): LocalDateTime = LocalDateTime.now(clock)
}
