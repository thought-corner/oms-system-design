package com.project.operation.client.dto

class OutgoingMessage(
    val topic: String,
    val key: String,
    val payload: ByteArray,
    val headers: Map<String, String>,
)

sealed interface PublishOutcome {

    data object Acked : PublishOutcome

    data class Unpublished(
        val kind: UnpublishedKind,
        val error: String,
    ) : PublishOutcome
}

enum class UnpublishedKind {
    RETRIABLE_FAILURE,
    PERMANENT_FAILURE,
    NOT_SENT,
}
