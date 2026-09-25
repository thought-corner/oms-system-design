package com.project.order.statemachine

import com.project.order.domain.SagaEvent
import com.project.order.domain.SagaStatus
import com.project.order.exception.OrderErrorCode
import org.springframework.statemachine.config.builders.StateMachineTransitionConfigurer
import org.springframework.stereotype.Component

@Component
class SagaStateMachine : EnumStateMachine<String, SagaStatus, SagaEvent>(
    states = SagaStatus.entries.toSet(),
    subject = "Saga",
    keyName = "sagaId",
    rejected = OrderErrorCode.INVALID_SAGA_STATE_TRANSITION,
) {

    override fun configure(transitions: StateMachineTransitionConfigurer<SagaStatus, SagaEvent>) {
        transitions
            .withExternal()
            .source(SagaStatus.RUNNING).target(SagaStatus.SUCCEEDED).event(SagaEvent.COMPLETE)
            .and()
            .withExternal()
            .source(SagaStatus.RUNNING).target(SagaStatus.COMPENSATING).event(SagaEvent.COMPENSATE)
            .and()
            .withExternal()
            .source(SagaStatus.COMPENSATING).target(SagaStatus.COMPENSATED).event(SagaEvent.COMPENSATION_DONE)
            .and()
            .withExternal()
            .source(SagaStatus.COMPENSATING).target(SagaStatus.COMPENSATION_FAILED).event(SagaEvent.COMPENSATION_FAIL)
            .and()
            .withExternal()
            .source(SagaStatus.COMPENSATION_FAILED).target(SagaStatus.COMPENSATING).event(SagaEvent.COMPENSATE)
            .and()
            .withExternal()
            .source(SagaStatus.COMPENSATION_FAILED).target(SagaStatus.COMPENSATED).event(SagaEvent.COMPENSATION_DONE)
            .and()
            .withInternal()
            .source(SagaStatus.COMPENSATING).event(SagaEvent.COMPENSATE)
            .and()
            .withInternal()
            .source(SagaStatus.RUNNING).event(SagaEvent.PROCEED)
            .and()
            .withInternal()
            .source(SagaStatus.COMPENSATING).event(SagaEvent.RECORD_CANCEL)
            .and()
            .withInternal()
            .source(SagaStatus.COMPENSATION_FAILED).event(SagaEvent.RECORD_CANCEL)
    }
}
