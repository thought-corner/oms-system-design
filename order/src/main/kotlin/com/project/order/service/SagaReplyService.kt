package com.project.order.service

import com.project.order.domain.Order
import com.project.order.domain.OrderSaga
import com.project.order.domain.SagaEvent
import com.project.order.domain.SagaStep
import com.project.order.repository.OrderRepository
import com.project.order.repository.OrderSagaRepository
import com.project.order.service.dto.ReplyDirection
import com.project.order.service.dto.ReplyOutcome
import com.project.order.service.dto.SagaReplyCommand
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDateTime

@Service
class SagaReplyService(
    private val orderRepository: OrderRepository,
    private val orderSagaRepository: OrderSagaRepository,
    private val sagaProgress: SagaProgress,
    private val commandOutbox: SagaCommandOutbox,
    private val clock: Clock,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun handle(reply: SagaReplyCommand) {
        val order = orderRepository.findWithWaitingLockById(reply.orderId)
            ?: return ignore(reply, "order not found")
        val saga = orderSagaRepository.findWithWaitingLockBySagaId(reply.sagaId)
            ?: return ignore(reply, "saga not found")
        if (saga.orderId != reply.orderId) {
            return ignore(reply, "saga belongs to orderId=${saga.orderId}")
        }

        when (reply.direction) {
            ReplyDirection.FORWARD -> when (reply.outcome) {
                ReplyOutcome.SUCCEEDED -> forwardSucceeded(order, saga, reply)
                ReplyOutcome.FAILED -> forwardFailed(saga, reply)
            }
            ReplyDirection.CANCEL -> canceled(order, saga, reply)
        }
    }

    private fun forwardSucceeded(order: Order, saga: OrderSaga, reply: SagaReplyCommand) {
        if (!saga.isAt(reply.step) || !sagaProgress.accepts(saga, SagaEvent.PROCEED)) {
            return ignore(reply, "stale forward success, status=${saga.status}, currentStep=${saga.currentStep}")
        }

        when (reply.step) {
            SagaStep.STOCK -> {
                val totalPrice = requireNotNull(reply.totalPrice) { "totalPrice missing: sagaId=${reply.sagaId}" }
                saga.stockCompleted(totalPrice, now())
                commandOutbox.appendForward(saga, order)
            }
            SagaStep.POINT -> {
                saga.pointCompleted(now())
                commandOutbox.appendForward(saga, order)
            }
            SagaStep.PAYMENT -> {
                saga.paymentCompleted(now())
                sagaProgress.complete(order, saga)
            }
        }
        applied(reply)
    }

    private fun forwardFailed(saga: OrderSaga, reply: SagaReplyCommand) {
        if (SagaFailureTranslator.isAlreadyCompensated(reply.code)) {
            return ignore(reply, "late forward rejected by participant guard")
        }

        val failure = SagaFailureTranslator.translate(reply.step, reply.code)

        if (saga.canFailAt(reply.step) && sagaProgress.accepts(saga, SagaEvent.PROCEED)) {
            sagaProgress.beginCompensation(saga, failure.errorCode.code, failure.unknownCodeError)
            return applied(reply)
        }

        if (sagaProgress.accepts(saga, SagaEvent.RECORD_CANCEL) && saga.recordFailure(failure.errorCode.code)) {
            failure.unknownCodeError?.let { saga.recordError(it) }
            log.info("Saga failure code filled during compensation: sagaId={}, code={}", saga.sagaId, saga.failureCode)
            return
        }

        ignore(reply, "stale forward failure, status=${saga.status}, currentStep=${saga.currentStep}, paymentDone=${saga.paymentDone}")
    }

    private fun canceled(order: Order, saga: OrderSaga, reply: SagaReplyCommand) {
        if (reply.outcome != ReplyOutcome.SUCCEEDED) {
            log.warn("Saga cancel reply without success ignored: sagaId={}, step={}, code={}", reply.sagaId, reply.step, reply.code)
            return
        }
        if (!sagaProgress.accepts(saga, SagaEvent.RECORD_CANCEL)) {
            return ignore(reply, "cancel reply outside compensation, status=${saga.status}")
        }
        if (!saga.canceled(reply.step, now())) {
            return ignore(reply, "duplicate cancel reply")
        }

        if (saga.allCanceled) {
            sagaProgress.close(order, saga)
        }
        applied(reply)
    }

    private fun applied(reply: SagaReplyCommand) {
        log.info(
            "Saga reply applied: sagaId={}, orderId={}, step={}, direction={}, outcome={}, code={}",
            reply.sagaId,
            reply.orderId,
            reply.step,
            reply.direction,
            reply.outcome,
            reply.code,
        )
    }

    private fun ignore(reply: SagaReplyCommand, reason: String) {
        log.info(
            "Saga reply ignored: sagaId={}, orderId={}, step={}, direction={}, outcome={}, code={}, messageType={}, reason={}",
            reply.sagaId,
            reply.orderId,
            reply.step,
            reply.direction,
            reply.outcome,
            reply.code,
            reply.messageType,
            reason,
        )
    }

    private fun now(): LocalDateTime = LocalDateTime.now(clock)
}
