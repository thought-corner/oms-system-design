package com.project.point.service

import com.project.common.exception.CommonErrorCode
import com.project.point.client.AlertSender
import com.project.point.client.DeadLetterAlert
import com.project.point.client.DeadLetterKind
import com.project.point.exception.NonRetryableExceptions
import com.project.point.service.dto.DeadLetterCommand
import com.project.point.service.dto.PointMessageType
import com.project.point.service.dto.ReplyDirection
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class DeadLetterAlertService(
    private val pointService: PointService,
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
        PointMessageType.entries.firstOrNull { it.name == deadLetter.messageType }
            ?.takeIf { it.direction == ReplyDirection.FORWARD }
            ?: return false
        val orderId = deadLetter.orderId?.toLongOrNull() ?: return false
        val sagaId = deadLetter.sagaId?.takeIf(::isSagaId) ?: return false

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
