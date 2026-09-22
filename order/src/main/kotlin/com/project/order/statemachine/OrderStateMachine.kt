package com.project.order.statemachine

import com.project.order.domain.OrderEvent
import com.project.order.domain.OrderStatus
import com.project.common.exception.BusinessException
import com.project.order.exception.OrderErrorCode
import org.slf4j.LoggerFactory
import org.springframework.messaging.support.MessageBuilder
import org.springframework.statemachine.StateMachine
import org.springframework.statemachine.StateMachineEventResult
import org.springframework.statemachine.config.StateMachineBuilder
import org.springframework.statemachine.listener.StateMachineListenerAdapter
import org.springframework.statemachine.transition.Transition
import org.springframework.stereotype.Component
import reactor.core.publisher.Mono

@Component
class OrderStateMachine {

    private val log = LoggerFactory.getLogger(javaClass)

    fun transition(orderId: Long, current: OrderStatus, event: OrderEvent): OrderStatus {
        val machine = build(orderId, current)
        machine.startReactively().block()

        val result = machine.sendEvent(Mono.just(MessageBuilder.withPayload(event).build())).blockLast()
        val next = machine.state.id
        machine.stopReactively().block()

        if (result == null || result.resultType != StateMachineEventResult.ResultType.ACCEPTED) {
            throw BusinessException(
                OrderErrorCode.INVALID_ORDER_STATE_TRANSITION,
                "orderId=$orderId, status=$current, event=$event",
            )
        }

        return next
    }

    private fun build(orderId: Long, initial: OrderStatus): StateMachine<OrderStatus, OrderEvent> {
        val builder = StateMachineBuilder.builder<OrderStatus, OrderEvent>()

        builder.configureConfiguration()
            .withConfiguration()
            .autoStartup(false)
            .listener(TransitionTracer(orderId))

        builder.configureStates()
            .withStates()
            .initial(initial)
            .states(OrderStatus.entries.toSet())

        builder.configureTransitions()
            .withExternal()
            .source(OrderStatus.CREATED).target(OrderStatus.PLACING).event(OrderEvent.PLACE)
            .and()
            .withExternal()
            .source(OrderStatus.FAILED).target(OrderStatus.PLACING).event(OrderEvent.PLACE)
            .and()
            .withExternal()
            .source(OrderStatus.PLACING).target(OrderStatus.COMPLETED).event(OrderEvent.COMPLETE)
            .and()
            .withExternal()
            .source(OrderStatus.PLACING).target(OrderStatus.FAILED).event(OrderEvent.FAIL)

        return builder.build()
    }

    private inner class TransitionTracer(private val orderId: Long) :
        StateMachineListenerAdapter<OrderStatus, OrderEvent>() {

        override fun transition(transition: Transition<OrderStatus, OrderEvent>) {
            val source = transition.source?.id ?: return
            log.info(
                "Order state transition accepted: orderId={}, {} -> {} on {}",
                orderId,
                source,
                transition.target.id,
                transition.trigger?.event,
            )
        }
    }
}
