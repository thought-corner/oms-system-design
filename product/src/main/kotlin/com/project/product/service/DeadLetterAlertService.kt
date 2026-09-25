package com.project.product.service

import com.project.common.exception.CommonErrorCode
import com.project.message.product.SagaDirection
import com.project.product.client.AlertSender
import com.project.product.client.DeadLetterAlert
import com.project.product.client.DeadLetterKind
import com.project.product.exception.NonRetryableExceptions
import com.project.product.service.dto.DeadLetterCommand
import com.project.product.service.dto.ProductCommandType
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class DeadLetterAlertService(
    private val productService: ProductService,
    private val alertSender: AlertSender,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun handle(deadLetter: DeadLetterCommand) {
        val kind = kindOf(deadLetter)
        val failedReplyWritten = kind == DeadLetterKind.POISON && recordForwardFailure(deadLetter)
        alertSender.send(
            DeadLetterAlert(
                topic = deadLetter.topic,
                orderId = deadLetter.orderId,
                sagaId = deadLetter.sagaId,
                messageType = deadLetter.messageType,
                exceptionClass = deadLetter.exceptionClass,
                exceptionMessage = deadLetter.exceptionMessage,
                kind = kind,
                failedReplyWritten = failedReplyWritten,
            ),
        )
    }

    private fun kindOf(deadLetter: DeadLetterCommand): DeadLetterKind =
        if (NonRetryableExceptions.includes(deadLetter.exceptionClass)) DeadLetterKind.POISON else DeadLetterKind.RETRY_EXHAUSTED

    private fun recordForwardFailure(deadLetter: DeadLetterCommand): Boolean {
        ProductCommandType.entries.firstOrNull { it.name == deadLetter.messageType }
            ?.takeIf { it.direction == SagaDirection.SAGA_DIRECTION_FORWARD }
            ?: return false
        val orderId = deadLetter.orderId?.toLongOrNull() ?: return false
        val sagaId = deadLetter.sagaId?.takeIf(::isSagaId) ?: return false

        return try {
            productService.replyBuyFailed(sagaId, orderId, CommonErrorCode.INTERNAL_ERROR)
            true
        } catch (e: RuntimeException) {
            log.error("Poisoned command failure reply was not written. sagaId={}, orderId={}", sagaId, orderId, e)
            false
        }
    }

    private fun isSagaId(value: String): Boolean =
        runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)
}
