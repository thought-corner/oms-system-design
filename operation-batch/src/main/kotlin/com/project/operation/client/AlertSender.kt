package com.project.operation.client

import java.time.LocalDateTime

interface AlertSender {

    fun send(alert: OutboxDelayAlert)

    fun send(alert: OutboxPublishFailedAlert)
}

data class OutboxDelayAlert(
    val schema: String,
    val pending: Long,
    val oldestPendingOccurredAt: LocalDateTime,
    val ageSeconds: Long,
    val failed: Long,
)

data class OutboxPublishFailedAlert(
    val schema: String,
    val outboxId: Long,
    val messageId: String,
    val topic: String,
    val sagaId: String,
    val messageType: String,
    val failCount: Int,
    val lastError: String,
)
