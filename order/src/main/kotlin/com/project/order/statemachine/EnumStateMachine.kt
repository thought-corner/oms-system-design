package com.project.order.statemachine

import com.project.common.exception.BusinessException
import com.project.common.exception.ErrorCode
import org.slf4j.LoggerFactory
import org.springframework.messaging.support.MessageBuilder
import org.springframework.statemachine.StateMachine
import org.springframework.statemachine.StateMachineEventResult
import org.springframework.statemachine.config.StateMachineBuilder
import org.springframework.statemachine.config.builders.StateMachineTransitionConfigurer
import org.springframework.statemachine.listener.StateMachineListenerAdapter
import org.springframework.statemachine.transition.Transition
import reactor.core.publisher.Mono

abstract class EnumStateMachine<K : Any, S : Enum<S>, E : Enum<E>>(
    private val states: Set<S>,
    private val subject: String,
    private val keyName: String,
    private val rejected: ErrorCode,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun transition(key: K, current: S, event: E): S {
        val machine = build(key, current)
        machine.startReactively().block()

        val result = machine.sendEvent(Mono.just(MessageBuilder.withPayload(event).build())).blockLast()
        val next = machine.state.id
        machine.stopReactively().block()

        if (result == null || result.resultType != StateMachineEventResult.ResultType.ACCEPTED) {
            throw BusinessException(rejected, "$keyName=$key, status=$current, event=$event")
        }

        return next
    }

    protected abstract fun configure(transitions: StateMachineTransitionConfigurer<S, E>)

    private fun build(key: K, initial: S): StateMachine<S, E> {
        val builder = StateMachineBuilder.builder<S, E>()

        builder.configureConfiguration()
            .withConfiguration()
            .autoStartup(false)
            .listener(TransitionTracer(key))

        builder.configureStates()
            .withStates()
            .initial(initial)
            .states(states)

        configure(builder.configureTransitions())

        return builder.build()
    }

    private inner class TransitionTracer(private val key: K) : StateMachineListenerAdapter<S, E>() {

        override fun transition(transition: Transition<S, E>) {
            val source = transition.source?.id ?: return
            log.info(
                "{} state transition accepted: {}={}, {} -> {} on {}",
                subject,
                keyName,
                key,
                source,
                transition.target.id,
                transition.trigger?.event,
            )
        }
    }
}
