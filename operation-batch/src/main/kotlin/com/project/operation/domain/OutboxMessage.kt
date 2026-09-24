package com.project.operation.domain

import java.time.LocalDateTime

data class OutboxMessage(
    val id: Long,
    val messageId: String,
    val topic: String,
    val messageKey: String,
    val sagaId: String,
    val messageType: String,
    val payload: String,
)

data class OutboxBacklog(
    val pending: Long,
    val oldestPendingOccurredAt: LocalDateTime?,
    val failed: Long,
)

data class PublishFailure(
    val message: OutboxMessage,
    val error: String,
)

data class OutboxFailure(
    val message: OutboxMessage,
    val failCount: Int,
    val status: OutboxStatus,
    val lastError: String,
)
