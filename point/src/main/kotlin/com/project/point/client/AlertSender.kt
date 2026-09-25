package com.project.point.client

interface AlertSender {

    fun send(alert: DeadLetterAlert)
}

enum class DeadLetterKind { POISON, RETRY_EXHAUSTED }

data class DeadLetterAlert(
    val topic: String,
    val orderId: String?,
    val sagaId: String?,
    val messageType: String?,
    val exceptionClass: String?,
    val exceptionMessage: String?,
    val kind: DeadLetterKind,
    val failedReplyWritten: Boolean,
)
