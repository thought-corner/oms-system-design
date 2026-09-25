package com.project.operation.client

import com.project.operation.client.dto.OutgoingMessage
import com.project.operation.client.dto.PublishOutcome
import com.project.operation.client.dto.UnpublishedKind
import org.apache.kafka.common.errors.RetriableException
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.header.internals.RecordHeader
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.support.SendResult
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

@Component
class KafkaMessagePublisher(
    private val kafkaTemplate: KafkaTemplate<String, ByteArray>,
) {

    fun publishAll(messages: List<OutgoingMessage>, deadline: Duration): List<PublishOutcome> {
        val deadlineAt = System.nanoTime() + deadline.toNanos()
        val unreachableTopics = mutableMapOf<String, String>()

        val sends = messages.map { message ->
            unreachableTopics[message.topic]?.let { return@map Skipped(it) }
            if (System.nanoTime() >= deadlineAt) {
                return@map Skipped("deadline exceeded before send")
            }
            send(message).also { sent ->
                if (sent is Rejected && isRetriable(sent.cause)) {
                    unreachableTopics[message.topic] = rootCause(sent.cause)
                }
            }
        }

        return sends.map { await(it, deadlineAt) }
    }

    private fun send(message: OutgoingMessage): Send = try {
        Sent(kafkaTemplate.send(message.toRecord()))
    } catch (e: RuntimeException) {
        Rejected(e)
    }

    private fun await(send: Send, deadlineAt: Long): PublishOutcome = when (send) {
        is Skipped -> PublishOutcome.Unpublished(UnpublishedKind.NOT_SENT, send.reason)
        is Rejected -> failed(send.cause)
        is Sent -> try {
            send.future.get((deadlineAt - System.nanoTime()).coerceAtLeast(0), TimeUnit.NANOSECONDS)
            PublishOutcome.Acked
        } catch (e: TimeoutException) {
            PublishOutcome.Unpublished(UnpublishedKind.RETRIABLE_FAILURE, "broker ack timed out")
        } catch (e: ExecutionException) {
            failed(e)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            PublishOutcome.Unpublished(UnpublishedKind.RETRIABLE_FAILURE, "interrupted")
        }
    }

    private fun failed(e: Throwable): PublishOutcome {
        val kind = if (isRetriable(e)) UnpublishedKind.RETRIABLE_FAILURE else UnpublishedKind.PERMANENT_FAILURE
        return PublishOutcome.Unpublished(kind, rootCause(e))
    }

    private fun isRetriable(e: Throwable): Boolean =
        generateSequence(e) { it.cause }.any { it is RetriableException }

    private fun rootCause(e: Throwable): String {
        val root = generateSequence(e) { it.cause }.last()
        return "${root.javaClass.simpleName}: ${root.message}"
    }

    private fun OutgoingMessage.toRecord(): ProducerRecord<String, ByteArray> =
        ProducerRecord<String, ByteArray>(topic, null, key, payload).also { record ->
            headers.forEach { (name, value) -> record.headers().add(RecordHeader(name, value.toByteArray(Charsets.UTF_8))) }
        }

    private sealed interface Send

    private class Sent(val future: CompletableFuture<SendResult<String, ByteArray>>) : Send

    private class Rejected(val cause: Throwable) : Send

    private class Skipped(val reason: String) : Send
}
