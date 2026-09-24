package com.project.order.client

import java.time.LocalDateTime

interface AlertSender {

    fun send(alert: SagaAlert)
}

sealed interface SagaAlert

data class ForwardRecoveryFailedAlert(
    val sagaId: String,
    val orderId: Long,
    val step: String,
    val attempts: Int,
    val lastError: String?,
) : SagaAlert

data class CompensationFailedAlert(
    val sagaId: String,
    val orderId: Long,
    val attempts: Int,
    val lastError: String?,
    val pendingCancels: List<String>,
) : SagaAlert

data class OutboxStalledAlert(
    val sagaId: String,
    val orderId: Long,
    val messageType: String,
    val status: String,
    val occurredAt: LocalDateTime,
    val failedCount: Int,
) : SagaAlert

enum class DeadLetterKind {
    POISON,
    RETRY_EXHAUSTED,
}

data class ReplyDeadLetterAlert(
    val kind: DeadLetterKind,
    val topic: String,
    val orderId: String?,
    val sagaId: String?,
    val messageType: String?,
    val exceptionClass: String?,
    val exceptionMessage: String?,
) : SagaAlert
