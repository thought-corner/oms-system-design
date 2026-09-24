package com.project.operation.client.dto

data class OutgoingMessage(
    val topic: String,
    val key: String?,
    val payload: String?,
    val headers: Map<String, String>,
)

enum class PublishResult {
    ACKED,
    RETRIABLE_FAILURE,
    PERMANENT_FAILURE,
    NOT_SENT,
}

data class PublishOutcome(
    val result: PublishResult,
    val error: String? = null,
) {
    val acked: Boolean
        get() = result == PublishResult.ACKED
}
