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
class CommandDeadLetterService(
    private val productService: ProductService,
    private val alertSender: AlertSender,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun handle(command: DeadLetterCommand) {
        val kind = kindOf(command)
        val failedReplyWritten = kind == DeadLetterKind.POISON && replyForwardFailed(command)
        alertSender.send(
            DeadLetterAlert(
                topic = command.topic,
                orderId = command.orderId,
                sagaId = command.sagaId,
                messageType = command.messageType,
                exceptionClass = command.exceptionClass,
                exceptionMessage = command.exceptionMessage,
                kind = kind,
                failedReplyWritten = failedReplyWritten,
            ),
        )
    }

    private fun kindOf(command: DeadLetterCommand): DeadLetterKind =
        if (NonRetryableExceptions.includes(command.exceptionClass)) DeadLetterKind.POISON else DeadLetterKind.RETRY_EXHAUSTED

    private fun replyForwardFailed(command: DeadLetterCommand): Boolean {
        ProductCommandType.entries.firstOrNull { it.name == command.messageType }
            ?.takeIf { it.direction == SagaDirection.SAGA_DIRECTION_FORWARD }
            ?: return false
        val orderId = command.orderId?.toLongOrNull() ?: return false
        val sagaId = command.sagaId?.takeIf(::isSagaId) ?: return false

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
