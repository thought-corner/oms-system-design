package com.project.point.client

interface AlertSender {

    fun send(alert: CommandDeadLetterAlert)
}

enum class DeadLetterKind { POISON, RETRY_EXHAUSTED }

data class CommandDeadLetterAlert(
    val topic: String,
    val orderId: String?,
    val sagaId: String?,
    val messageType: String?,
    val exceptionClass: String?,
    val exceptionMessage: String?,
    val kind: DeadLetterKind,
    val failedReplyWritten: Boolean,
)
