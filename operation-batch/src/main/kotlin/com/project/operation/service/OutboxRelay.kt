package com.project.operation.service

import com.project.operation.client.AlertSender
import com.project.operation.client.KafkaMessagePublisher
import com.project.operation.client.OutboxBacklogAlert
import com.project.operation.client.OutboxPublishFailedAlert
import com.project.operation.client.dto.OutgoingMessage
import com.project.operation.client.dto.PublishOutcome
import com.project.operation.client.dto.PublishResult
import com.project.operation.domain.OutboxFailure
import com.project.operation.domain.OutboxMessage
import com.project.operation.domain.OutboxSource
import com.project.operation.domain.OutboxStatus
import com.project.operation.domain.PublishFailure
import com.project.operation.service.policy.OutboxRelayPolicy
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap

@Component
class OutboxRelay(
    private val outboxService: OutboxService,
    private val publisher: KafkaMessagePublisher,
    private val alertSender: AlertSender,
    private val clock: Clock,
) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val failingSources = ConcurrentHashMap<OutboxSource, String>()
    private val unreachableSources = ConcurrentHashMap<OutboxSource, String>()

    @Scheduled(fixedDelay = OutboxRelayPolicy.POLL_INTERVAL_MS)
    fun relay() {
        OutboxSource.entries.forEach { relay(it) }
    }

    fun relay(source: OutboxSource) {
        try {
            val claimed = outboxService.claim(source)
            if (claimed.isNotEmpty()) {
                publish(source, claimed)
            }
            recovered(source)
        } catch (e: RuntimeException) {
            failed(source, e)
        }
    }

    @Scheduled(cron = OutboxRelayPolicy.PURGE_CRON, zone = OutboxRelayPolicy.PURGE_ZONE)
    fun purge() {
        OutboxSource.entries.forEach { purge(it) }
    }

    @Scheduled(fixedDelay = OutboxRelayPolicy.BACKLOG_CHECK_INTERVAL_MS)
    fun watchBacklog() {
        OutboxSource.entries.forEach { watchBacklog(it) }
    }

    private fun publish(source: OutboxSource, claimed: List<OutboxMessage>) {
        val outcomes = publisher.publishAll(claimed.map { it.toOutgoingMessage() }, OutboxRelayPolicy.PUBLISH_DEADLINE)
        val results = claimed.zip(outcomes)
        val acked = results.filter { (_, outcome) -> outcome.acked }.map { (message, _) -> message.id }
        if (acked.isNotEmpty()) {
            outboxService.markPublished(source, acked)
            brokerReachable(source)
        }

        val unpublished = results.filterNot { (_, outcome) -> outcome.acked }
        if (unpublished.isEmpty()) {
            return
        }
        if (acked.isEmpty() && unpublished.brokerUnreachable()) {
            brokerUnreachable(source, unpublished)
            return
        }

        val failures = unpublished
            .filter { (_, outcome) -> outcome.countsAsFailure(brokerAcked = acked.isNotEmpty()) }
            .map { (message, outcome) -> PublishFailure(message, outcome.error ?: UNKNOWN_ERROR) }
        val (firstMessage, firstOutcome) = unpublished.first()
        log.warn(
            "Outbox publish incomplete, lease expiry will retry: schema={}, published={}, failed={}, uncounted={}, firstId={}, firstTopic={}, cause={}",
            source.schema,
            acked.size,
            failures.size,
            unpublished.size - failures.size,
            firstMessage.id,
            firstMessage.topic,
            firstOutcome.error,
        )
        if (failures.isNotEmpty()) {
            outboxService.recordFailures(source, failures)
                .filter { it.status == OutboxStatus.FAILED }
                .forEach { alertSender.send(it.toAlert(source)) }
        }
    }

    private fun List<Pair<OutboxMessage, PublishOutcome>>.brokerUnreachable(): Boolean =
        none { (_, outcome) -> outcome.result == PublishResult.PERMANENT_FAILURE } &&
            any { (_, outcome) -> outcome.result == PublishResult.RETRIABLE_FAILURE }

    private fun PublishOutcome.countsAsFailure(brokerAcked: Boolean): Boolean =
        when (result) {
            PublishResult.PERMANENT_FAILURE -> true
            PublishResult.RETRIABLE_FAILURE -> brokerAcked
            PublishResult.ACKED, PublishResult.NOT_SENT -> false
        }

    private fun brokerUnreachable(source: OutboxSource, unpublished: List<Pair<OutboxMessage, PublishOutcome>>) {
        val (firstMessage, firstOutcome) = unpublished.first { (_, outcome) -> outcome.result == PublishResult.RETRIABLE_FAILURE }
        val cause = firstOutcome.error ?: UNKNOWN_ERROR
        if (unreachableSources.putIfAbsent(source, cause) == null) {
            log.warn(
                "Kafka broker unreachable, outbox rows stay PENDING without counting failures and retry after the lease: schema={}, unpublished={}, firstId={}, firstTopic={}, cause={}",
                source.schema,
                unpublished.size,
                firstMessage.id,
                firstMessage.topic,
                cause,
            )
        }
    }

    private fun brokerReachable(source: OutboxSource) {
        if (unreachableSources.remove(source) != null) {
            log.warn("Kafka broker reachable again, outbox publishing resumed: schema={}", source.schema)
        }
    }

    private fun purge(source: OutboxSource) {
        try {
            var total = 0
            do {
                val deleted = outboxService.purgeChunk(source)
                total += deleted
            } while (deleted == OutboxRelayPolicy.PURGE_CHUNK)
            log.info("Outbox purged: schema={}, deleted={}", source.schema, total)
        } catch (e: RuntimeException) {
            log.warn("Outbox purge failed: schema={}, cause={}", source.schema, e.message)
        }
    }

    private fun watchBacklog(source: OutboxSource) {
        try {
            val backlog = outboxService.backlogOf(source)
            if (backlog.failed > 0) {
                log.warn("Outbox has FAILED rows awaiting an operator: schema={}, failed={}", source.schema, backlog.failed)
            }
            val oldest = backlog.oldestPendingOccurredAt ?: return
            val age = Duration.between(oldest, LocalDateTime.now(clock))
            if (age > OutboxRelayPolicy.BACKLOG_ALERT_AGE) {
                alertSender.send(OutboxBacklogAlert(source.schema, backlog.pending, oldest, age.seconds, backlog.failed))
            }
        } catch (e: RuntimeException) {
            log.warn("Outbox backlog check failed: schema={}, cause={}", source.schema, e.message)
        }
    }

    private fun failed(source: OutboxSource, e: RuntimeException) {
        val cause = "${e.javaClass.simpleName}: ${e.message}"
        if (failingSources.put(source, cause) != cause) {
            log.warn("Outbox relay failed, will keep polling: schema={}, cause={}", source.schema, cause)
        }
    }

    private fun recovered(source: OutboxSource) {
        if (failingSources.remove(source) != null) {
            log.info("Outbox relay recovered: schema={}", source.schema)
        }
    }

    private fun OutboxMessage.toOutgoingMessage() = OutgoingMessage(
        topic = topic,
        key = messageKey,
        payload = payload,
        headers = mapOf(
            MessageHeaders.MESSAGE_ID to messageId,
            MessageHeaders.SAGA_ID to sagaId,
            MessageHeaders.MESSAGE_TYPE to messageType,
        ),
    )

    private fun OutboxFailure.toAlert(source: OutboxSource) = OutboxPublishFailedAlert(
        schema = source.schema,
        outboxId = message.id,
        messageId = message.messageId,
        topic = message.topic,
        sagaId = message.sagaId,
        messageType = message.messageType,
        failCount = failCount,
        lastError = lastError,
    )

    companion object {
        private const val UNKNOWN_ERROR = "unknown"
    }
}
