package com.project.order.statemachine

import com.project.order.domain.SagaEvent
import com.project.order.domain.SagaStatus
import com.project.order.exception.OrderErrorCode
import com.project.common.exception.BusinessException
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
class SagaStateMachine {

    private val log = LoggerFactory.getLogger(javaClass)

    fun transition(sagaId: String, current: SagaStatus, event: SagaEvent): SagaStatus {
        val machine = build(sagaId, current)
        machine.startReactively().block()

        val result = machine.sendEvent(Mono.just(MessageBuilder.withPayload(event).build())).blockLast()
        val next = machine.state.id
        machine.stopReactively().block()

        if (result == null || result.resultType != StateMachineEventResult.ResultType.ACCEPTED) {
            throw BusinessException(
                OrderErrorCode.INVALID_SAGA_STATE_TRANSITION,
                "sagaId=$sagaId, status=$current, event=$event",
            )
        }

        return next
    }

    private fun build(sagaId: String, initial: SagaStatus): StateMachine<SagaStatus, SagaEvent> {
        val builder = StateMachineBuilder.builder<SagaStatus, SagaEvent>()

        builder.configureConfiguration()
            .withConfiguration()
            .autoStartup(false)
            .listener(TransitionTracer(sagaId))

        builder.configureStates()
            .withStates()
            .initial(initial)
            .states(SagaStatus.entries.toSet())

        builder.configureTransitions()
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

        return builder.build()
    }

    private inner class TransitionTracer(private val sagaId: String) :
        StateMachineListenerAdapter<SagaStatus, SagaEvent>() {

        override fun transition(transition: Transition<SagaStatus, SagaEvent>) {
            val source = transition.source?.id ?: return
            log.info(
                "Saga state transition accepted: sagaId={}, {} -> {} on {}",
                sagaId,
                source,
                transition.target.id,
                transition.trigger?.event,
            )
        }
    }
}
