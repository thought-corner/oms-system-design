package com.project.operation.service.worker

import com.project.operation.client.AlertSender
import com.project.operation.client.KafkaMessagePublisher
import com.project.operation.client.OutboxPublishFailedAlert
import com.project.operation.client.dto.OutgoingMessage
import com.project.operation.client.dto.PublishOutcome
import com.project.operation.domain.OutboxFailure
import com.project.operation.domain.OutboxMessage
import com.project.operation.domain.OutboxSource
import com.project.operation.service.MessageHeaders
import com.project.operation.service.OutboxService
import com.project.operation.service.policy.OutboxRelayPolicy
import com.project.operation.service.policy.PublishFailureCounting
import com.project.operation.service.policy.PublishVerdict
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class OutboxRelay(
    private val outboxService: OutboxService,
    private val publisher: KafkaMessagePublisher,
    private val alertSender: AlertSender,
) {

    private val log = LoggerFactory.getLogger(javaClass)

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
        } catch (e: RuntimeException) {
            log.warn("outbox 릴레이가 실패했지만 폴링을 계속합니다. schema={}, cause={}", source.schema, "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun publish(source: OutboxSource, claimed: List<OutboxMessage>) {
        val outcomes = publisher.publishAll(claimed.map { it.toOutgoingMessage() }, OutboxRelayPolicy.PUBLISH_DEADLINE)
        val results = claimed.zip(outcomes)
        val acked = results.filter { (_, outcome) -> outcome is PublishOutcome.Acked }.map { (message, _) -> message.id }
        if (acked.isNotEmpty()) {
            outboxService.markPublished(source, acked)
        }

        when (val verdict = PublishFailureCounting.classify(results)) {
            PublishVerdict.AllAcked -> Unit
            is PublishVerdict.BrokerUnreachable -> brokerUnreachable(source, verdict)
            is PublishVerdict.Unsent -> unsent(source, acked.size, verdict)
            is PublishVerdict.Counted -> recordFailures(source, acked.size, verdict)
        }
    }

    private fun recordFailures(source: OutboxSource, published: Int, verdict: PublishVerdict.Counted) {
        log.warn(
            "outbox 발행이 일부 끝나지 않아 임대가 끝나면 다시 시도합니다. schema={}, published={}, failed={}, uncounted={}, firstId={}, firstTopic={}, cause={}",
            source.schema,
            published,
            verdict.failures.size,
            verdict.uncounted,
            verdict.firstMessage.id,
            verdict.firstMessage.topic,
            verdict.firstError,
        )
        outboxService.recordFailures(source, verdict.failures)
            .forEach { alertSender.send(it.toAlert(source)) }
    }

    private fun brokerUnreachable(source: OutboxSource, verdict: PublishVerdict.BrokerUnreachable) {
        log.warn(
            "Kafka 브로커에 닿지 않아 outbox 행을 실패로 세지 않고 PENDING 으로 둔 채 임대가 끝나면 다시 시도합니다. schema={}, unpublished={}, firstId={}, firstTopic={}, cause={}",
            source.schema,
            verdict.unpublished,
            verdict.firstMessage.id,
            verdict.firstMessage.topic,
            verdict.cause,
        )
    }

    private fun unsent(source: OutboxSource, published: Int, verdict: PublishVerdict.Unsent) {
        log.warn(
            "outbox 행 일부를 보내지 못해 실패로 세지 않고 임대가 끝나면 다시 시도합니다. schema={}, published={}, unsent={}, firstId={}, firstTopic={}, reason={}",
            source.schema,
            published,
            verdict.unsent,
            verdict.firstMessage.id,
            verdict.firstMessage.topic,
            verdict.reason,
        )
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
}
