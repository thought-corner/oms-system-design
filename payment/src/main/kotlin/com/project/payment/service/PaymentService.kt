package com.project.payment.service

import com.project.payment.domain.Payment
import com.project.payment.domain.PaymentEvent
import com.project.common.exception.BusinessException
import com.project.payment.domain.PaymentStatus
import com.project.payment.exception.PaymentErrorCode
import com.project.payment.repository.PaymentRepository
import com.project.payment.service.dto.PayCancelCommand
import com.project.payment.service.dto.PayCommand
import com.project.payment.service.dto.PayResult
import com.project.payment.statemachine.PaymentStateMachine
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.LocalDateTime

@Service
class PaymentService(
    private val paymentRepository: PaymentRepository,
    private val paymentStateMachine: PaymentStateMachine,
    private val clock: Clock,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun pay(command: PayCommand): PayResult {
        paymentRepository.findBySagaId(command.sagaId)?.let { return it.toResult() }

        paymentRepository.findByOrderIdAndStatus(command.orderId, PaymentStatus.PAID)?.let {
            throw BusinessException(PaymentErrorCode.ALREADY_PAID, "orderId=${command.orderId}")
        }

        simulateExternalPaymentGatewayLatency()

        val payment = paymentRepository.save(
            Payment(
                sagaId = command.sagaId,
                orderId = command.orderId,
                userId = command.userId,
                amount = command.amount,
                paidAt = LocalDateTime.now(clock),
            ),
        )

        return payment.toResult()
    }

    @Transactional
    fun cancel(command: PayCancelCommand) {
        val payment = paymentRepository.findBySagaId(command.sagaId) ?: return
        if (payment.status == PaymentStatus.CANCELED) {
            return
        }

        val paymentId = requireNotNull(payment.id)
        val next = paymentStateMachine.transition(paymentId, payment.status, PaymentEvent.CANCEL)
        payment.transitionTo(next)
        log.info("Payment state transition applied: paymentId={}, {} -> {}", paymentId, PaymentStatus.PAID, next)
    }

    private fun simulateExternalPaymentGatewayLatency() {
        Thread.sleep(EXTERNAL_PAYMENT_GATEWAY_LATENCY.toMillis())
    }

    private fun Payment.toResult(): PayResult = PayResult(paymentId = requireNotNull(id), paidAt = paidAt)

    companion object {
        val EXTERNAL_PAYMENT_GATEWAY_LATENCY: Duration = Duration.ofSeconds(3)
    }
}
