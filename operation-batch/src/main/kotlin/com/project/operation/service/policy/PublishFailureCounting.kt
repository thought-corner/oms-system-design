package com.project.operation.service.policy

import com.project.operation.client.dto.PublishOutcome
import com.project.operation.client.dto.UnpublishedKind
import com.project.operation.domain.OutboxMessage
import com.project.operation.domain.PublishFailure

object PublishFailureCounting {

    fun classify(results: List<Pair<OutboxMessage, PublishOutcome>>): PublishVerdict {
        val brokerAcked = results.any { (_, outcome) -> outcome is PublishOutcome.Acked }
        val unpublished = results.mapNotNull { (message, outcome) ->
            (outcome as? PublishOutcome.Unpublished)?.let { message to it }
        }
        if (unpublished.isEmpty()) {
            return PublishVerdict.AllAcked
        }
        if (!brokerAcked && unpublished.brokerUnreachable()) {
            val (firstMessage, firstOutcome) = unpublished.first { (_, outcome) -> outcome.kind == UnpublishedKind.RETRIABLE_FAILURE }
            return PublishVerdict.BrokerUnreachable(
                unpublished = unpublished.size,
                firstMessage = firstMessage,
                cause = firstOutcome.error,
            )
        }
        val (firstMessage, firstOutcome) = unpublished.first()
        val failures = unpublished
            .filter { (_, outcome) -> outcome.countsAsFailure(brokerAcked) }
            .map { (message, outcome) -> PublishFailure(message, outcome.error) }
        if (failures.isEmpty()) {
            return PublishVerdict.Unsent(
                unsent = unpublished.size,
                firstMessage = firstMessage,
                reason = firstOutcome.error,
            )
        }
        return PublishVerdict.Counted(
            failures = failures,
            uncounted = unpublished.size - failures.size,
            firstMessage = firstMessage,
            firstError = firstOutcome.error,
        )
    }

    private fun List<Pair<OutboxMessage, PublishOutcome.Unpublished>>.brokerUnreachable(): Boolean =
        none { (_, outcome) -> outcome.kind == UnpublishedKind.PERMANENT_FAILURE } &&
            any { (_, outcome) -> outcome.kind == UnpublishedKind.RETRIABLE_FAILURE }

    private fun PublishOutcome.Unpublished.countsAsFailure(brokerAcked: Boolean): Boolean =
        when (kind) {
            UnpublishedKind.PERMANENT_FAILURE -> true
            UnpublishedKind.RETRIABLE_FAILURE -> brokerAcked
            UnpublishedKind.NOT_SENT -> false
        }
}

sealed interface PublishVerdict {

    data object AllAcked : PublishVerdict

    data class BrokerUnreachable(
        val unpublished: Int,
        val firstMessage: OutboxMessage,
        val cause: String,
    ) : PublishVerdict

    data class Unsent(
        val unsent: Int,
        val firstMessage: OutboxMessage,
        val reason: String,
    ) : PublishVerdict

    data class Counted(
        val failures: List<PublishFailure>,
        val uncounted: Int,
        val firstMessage: OutboxMessage,
        val firstError: String,
    ) : PublishVerdict
}
