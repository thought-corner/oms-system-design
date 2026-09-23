package com.project.payment.service

import com.project.payment.domain.Payment
import com.project.payment.domain.PaymentEvent
import com.project.payment.domain.SagaGuard
import com.project.payment.domain.SagaGuardKind
import com.project.common.exception.BusinessException
import com.project.payment.domain.PaymentStatus
import com.project.payment.exception.PaymentErrorCode
import com.project.payment.repository.PaymentRepository
import com.project.payment.repository.SagaGuardRepository
import com.project.payment.service.dto.PayCancelCommand
import com.project.payment.service.dto.PayCommand
import com.project.payment.service.dto.PayResult
import com.project.payment.statemachine.PaymentStateMachine
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.LocalDateTime

@Service
class PaymentService(
    private val paymentRepository: PaymentRepository,
    private val guardRepository: SagaGuardRepository,
    private val paymentStateMachine: PaymentStateMachine,
    private val clock: Clock,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun pay(command: PayCommand): PayResult {
        val guard = lockGuard(command.sagaId, SagaGuardKind.FORWARD)
        val existing = paymentRepository.findBySagaId(command.sagaId)
        if (guard.kind == SagaGuardKind.CANCEL || existing?.status == PaymentStatus.CANCELED) {
            throw BusinessException(PaymentErrorCode.SAGA_ALREADY_COMPENSATED, "sagaId=${command.sagaId}")
        }

        existing?.let { return it.toResult() }

        paymentRepository.findByPaidOrderId(command.orderId)?.let {
            throw BusinessException(PaymentErrorCode.ALREADY_PAID, "orderId=${command.orderId}")
        }

        simulateExternalPaymentGatewayLatency()

        val payment = try {
            paymentRepository.save(
                Payment(
                    sagaId = command.sagaId,
                    orderId = command.orderId,
                    userId = command.userId,
                    amount = command.amount,
                    paidAt = LocalDateTime.now(clock),
                ),
            )
        } catch (e: DataIntegrityViolationException) {
            if (e.mostSpecificCause.message?.contains(Payment.UK_PAID_ORDER_ID) == true) {
                throw BusinessException(PaymentErrorCode.ALREADY_PAID, "orderId=${command.orderId}")
            }
            throw e
        }

        return payment.toResult()
    }

    @Transactional
    fun cancel(command: PayCancelCommand) {
        lockGuard(command.sagaId, SagaGuardKind.CANCEL)

        val payment = paymentRepository.findBySagaId(command.sagaId) ?: return
        if (payment.status == PaymentStatus.CANCELED) {
            return
        }

        val paymentId = requireNotNull(payment.id)
        val current = payment.status
        val next = paymentStateMachine.transition(paymentId, current, PaymentEvent.CANCEL)
        payment.transitionTo(next)
        log.info("Payment state transition applied: paymentId={}, {} -> {}", paymentId, current, next)
    }

    private fun lockGuard(sagaId: String, kind: SagaGuardKind): SagaGuard {
        guardRepository.insertIfAbsent(sagaId, kind.name, LocalDateTime.now(clock))

        return requireNotNull(guardRepository.findWithLockBySagaId(sagaId)) { "sagaId=$sagaId" }
    }

    private fun simulateExternalPaymentGatewayLatency() {
        Thread.sleep(EXTERNAL_PAYMENT_GATEWAY_LATENCY.toMillis())
    }

    private fun Payment.toResult(): PayResult = PayResult(paymentId = requireNotNull(id), paidAt = paidAt)

    companion object {
        val EXTERNAL_PAYMENT_GATEWAY_LATENCY: Duration = Duration.ofSeconds(3)
    }
}
