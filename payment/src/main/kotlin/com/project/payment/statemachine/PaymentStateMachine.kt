package com.project.payment.statemachine

import com.project.common.exception.BusinessException
import com.project.payment.domain.PaymentEvent
import com.project.payment.domain.PaymentStatus
import com.project.payment.exception.PaymentErrorCode
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
class PaymentStateMachine {

    private val log = LoggerFactory.getLogger(javaClass)

    fun transition(paymentId: Long, current: PaymentStatus, event: PaymentEvent): PaymentStatus {
        val machine = build(paymentId, current)
        machine.startReactively().block()

        val result = machine.sendEvent(Mono.just(MessageBuilder.withPayload(event).build())).blockLast()
        val next = machine.state.id
        machine.stopReactively().block()

        if (result == null || result.resultType != StateMachineEventResult.ResultType.ACCEPTED) {
            throw BusinessException(
                PaymentErrorCode.INVALID_PAYMENT_STATE_TRANSITION,
                "paymentId=$paymentId, status=$current, event=$event",
            )
        }

        return next
    }

    private fun build(paymentId: Long, initial: PaymentStatus): StateMachine<PaymentStatus, PaymentEvent> {
        val builder = StateMachineBuilder.builder<PaymentStatus, PaymentEvent>()

        builder.configureConfiguration()
            .withConfiguration()
            .autoStartup(false)
            .listener(TransitionTracer(paymentId))

        builder.configureStates()
            .withStates()
            .initial(initial)
            .states(PaymentStatus.entries.toSet())

        builder.configureTransitions()
            .withExternal()
            .source(PaymentStatus.PAID).target(PaymentStatus.CANCELED).event(PaymentEvent.CANCEL)

        return builder.build()
    }

    private inner class TransitionTracer(private val paymentId: Long) :
        StateMachineListenerAdapter<PaymentStatus, PaymentEvent>() {

        override fun transition(transition: Transition<PaymentStatus, PaymentEvent>) {
            val source = transition.source?.id ?: return
            log.info(
                "Payment state transition accepted: paymentId={}, {} -> {} on {}",
                paymentId,
                source,
                transition.target.id,
                transition.trigger?.event,
            )
        }
    }
}
