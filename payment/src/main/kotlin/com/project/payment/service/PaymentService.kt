package com.project.payment.service

import com.project.common.exception.BusinessException
import com.project.common.exception.ErrorCode
import com.project.payment.domain.Payment
import com.project.payment.domain.PaymentEvent
import com.project.payment.exception.PaymentErrorCode
import com.project.payment.repository.PaymentRepository
import com.project.payment.service.dto.PayCancelCommand
import com.project.payment.service.dto.PayCommand
import com.project.payment.service.dto.PaymentMessageType
import com.project.payment.statemachine.PaymentStateMachine
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDateTime

@Service
class PaymentService(
    private val paymentRepository: PaymentRepository,
    private val sagaGuardLock: SagaGuardLock,
    private val paymentStateMachine: PaymentStateMachine,
    private val sagaReplyOutbox: SagaReplyOutbox,
    private val clock: Clock,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun pay(command: PayCommand) {
        sagaGuardLock.lockForward(command.sagaId)
        val previousPayment = paymentRepository.findBySagaId(command.sagaId)
        if (previousPayment?.isCanceled() == true) {
            throw BusinessException(PaymentErrorCode.SAGA_ALREADY_COMPENSATED, "sagaId=${command.sagaId}")
        }

        if (previousPayment == null) {
            approve(command)
        }

        sagaReplyOutbox.succeeded(PaymentMessageType.PAYMENT_PAY, command.sagaId, command.orderId)
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun recordPayFailure(command: PayCommand, errorCode: ErrorCode) {
        recordPayFailure(command.sagaId, command.orderId, errorCode)
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun recordPayFailure(sagaId: String, orderId: Long, errorCode: ErrorCode) {
        sagaReplyOutbox.failed(PaymentMessageType.PAYMENT_PAY, sagaId, orderId, errorCode)
    }

    @Transactional
    fun cancel(command: PayCancelCommand) {
        sagaGuardLock.lockCancel(command.sagaId)

        paymentRepository.findBySagaId(command.sagaId)
            ?.takeUnless { it.isCanceled() }
            ?.let { cancelPaid(it) }

        sagaReplyOutbox.succeeded(PaymentMessageType.PAYMENT_CANCEL, command.sagaId, command.orderId)
    }

    private fun approve(command: PayCommand) {
        paymentRepository.findByPaidOrderId(command.orderId)?.let {
            throw BusinessException(PaymentErrorCode.ALREADY_PAID, "orderId=${command.orderId}")
        }

        try {
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
            if (isOrderAlreadyPaid(e)) {
                throw BusinessException(PaymentErrorCode.ALREADY_PAID, "orderId=${command.orderId}")
            }
            throw e
        }
    }

    private fun cancelPaid(payment: Payment) {
        val paymentId = requireNotNull(payment.id)
        val currentStatus = payment.status
        val nextStatus = paymentStateMachine.transition(paymentId, currentStatus, PaymentEvent.CANCEL)
        payment.transitionTo(nextStatus)
        log.info("Payment state transition applied: paymentId={}, {} -> {}", paymentId, currentStatus, nextStatus)
    }

    private fun isOrderAlreadyPaid(e: DataIntegrityViolationException): Boolean =
        e.mostSpecificCause.message?.contains(Payment.UK_PAID_ORDER_ID) == true
}
