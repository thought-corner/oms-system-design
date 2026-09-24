package com.project.order.statemachine

import com.project.order.domain.OrderEvent
import com.project.order.domain.OrderStatus
import com.project.order.exception.OrderErrorCode
import org.springframework.statemachine.config.builders.StateMachineTransitionConfigurer
import org.springframework.stereotype.Component

@Component
class OrderStateMachine : EnumStateMachine<Long, OrderStatus, OrderEvent>(
    states = OrderStatus.entries.toSet(),
    subject = "Order",
    keyName = "orderId",
    rejected = OrderErrorCode.INVALID_ORDER_STATE_TRANSITION,
) {

    override fun configure(transitions: StateMachineTransitionConfigurer<OrderStatus, OrderEvent>) {
        transitions
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
    }
}
