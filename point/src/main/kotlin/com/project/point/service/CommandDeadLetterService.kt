package com.project.point.service

import com.project.common.exception.CommonErrorCode
import com.project.point.client.AlertSender
import com.project.point.client.CommandDeadLetterAlert
import com.project.point.client.DeadLetterKind
import com.project.point.exception.NonRetryableExceptions
import com.project.point.service.dto.DeadLetterCommand
import com.project.point.service.dto.PointMessageType
import com.project.point.service.dto.SagaDirection
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class CommandDeadLetterService(
    private val pointService: PointService,
    private val alertSender: AlertSender,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun handle(command: DeadLetterCommand) {
        val kind = kindOf(command)
        val failedReplyWritten = kind == DeadLetterKind.POISON && recordForwardFailure(command)
        alertSender.send(
            CommandDeadLetterAlert(
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

    private fun recordForwardFailure(command: DeadLetterCommand): Boolean {
        PointMessageType.entries.firstOrNull { it.name == command.messageType }
            ?.takeIf { it.direction == SagaDirection.FORWARD }
            ?: return false
        val orderId = command.orderId?.toLongOrNull() ?: return false
        val sagaId = command.sagaId?.takeIf(::isSagaId) ?: return false

        return try {
            pointService.recordUseFailure(sagaId, orderId, CommonErrorCode.INTERNAL_ERROR)
            true
        } catch (e: RuntimeException) {
            log.error("Poisoned command failure reply was not written. sagaId={}, orderId={}", sagaId, orderId, e)
            false
        }
    }

    private fun isSagaId(value: String): Boolean =
        runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)
}
