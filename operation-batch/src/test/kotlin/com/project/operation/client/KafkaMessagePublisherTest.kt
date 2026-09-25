package com.project.operation.client

import com.project.operation.client.dto.OutgoingMessage
import com.project.operation.client.dto.PublishOutcome
import com.project.operation.client.dto.UnpublishedKind
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.errors.InvalidTopicException
import org.apache.kafka.common.errors.NetworkException
import org.apache.kafka.common.errors.RecordTooLargeException
import org.apache.kafka.common.errors.SerializationException
import org.apache.kafka.common.errors.TimeoutException
import org.apache.kafka.common.errors.TopicAuthorizationException
import org.springframework.kafka.KafkaException
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.support.SendResult
import java.time.Duration
import java.util.concurrent.CompletableFuture

private fun message(topic: String, key: String = "10") =
    OutgoingMessage(topic = topic, key = key, payload = byteArrayOf(0x0A, 0x02), headers = mapOf("sagaId" to "saga-1"))

private fun PublishOutcome.unpublished(): PublishOutcome.Unpublished = shouldBeInstanceOf<PublishOutcome.Unpublished>()

private fun List<PublishOutcome>.kinds(): List<UnpublishedKind> = map { it.unpublished().kind }

private fun acked(): CompletableFuture<SendResult<String, ByteArray>> = CompletableFuture.completedFuture(mockk())

private fun failed(cause: Throwable): CompletableFuture<SendResult<String, ByteArray>> =
    CompletableFuture<SendResult<String, ByteArray>>().also { it.completeExceptionally(KafkaException("send failed", cause)) }

class KafkaMessagePublisherTest : BehaviorSpec({

    Given("메타데이터를 받지 못해 보내는 순간 예외를 던지는 토픽의 행 둘과 정상 토픽의 행") {
        val template = mockk<KafkaTemplate<String, ByteArray>>()
        every { template.send(match<ProducerRecord<String, ByteArray>> { it.topic() == "no.such.topic" }) } throws
            KafkaException("Send failed", TimeoutException("Topic no.such.topic not present in metadata"))
        every { template.send(match<ProducerRecord<String, ByteArray>> { it.topic() == "cmd.product" }) } returns acked()
        val publisher = KafkaMessagePublisher(template)

        When("한 배치로 보내면") {
            val outcomes = publisher.publishAll(
                listOf(message("no.such.topic"), message("no.such.topic"), message("cmd.product")),
                Duration.ofSeconds(5),
            )

            Then("깨진 토픽은 한 번만 시도해 재시도 가능한 실패로 두고 같은 배치의 나머지는 보내지 않으며, 정상 행은 확인받는다") {
                outcomes[0].unpublished().kind shouldBe UnpublishedKind.RETRIABLE_FAILURE
                outcomes[1].unpublished().kind shouldBe UnpublishedKind.NOT_SENT
                outcomes[2] shouldBe PublishOutcome.Acked
                outcomes[0].unpublished().error shouldContain "TimeoutException"
                outcomes[1].unpublished().error shouldContain "TimeoutException"
                verify(exactly = 1) { template.send(match<ProducerRecord<String, ByteArray>> { it.topic() == "no.such.topic" }) }
            }
        }
    }

    Given("같은 토픽에서 한 행만 직렬화에 실패해 보내는 순간 예외를 던지는 프로듀서") {
        val template = mockk<KafkaTemplate<String, ByteArray>>()
        every { template.send(match<ProducerRecord<String, ByteArray>> { it.key() == "10" }) } throws
            SerializationException("Can't convert value")
        every { template.send(match<ProducerRecord<String, ByteArray>> { it.key() == "11" }) } returns acked()
        val publisher = KafkaMessagePublisher(template)

        When("같은 토픽의 두 행을 보내면") {
            val outcomes = publisher.publishAll(
                listOf(message("cmd.point", key = "10"), message("cmd.point", key = "11")),
                Duration.ofSeconds(1),
            )

            Then("행의 문제라 그 행만 영구 실패로 두고 같은 토픽의 다음 행은 보낸다") {
                outcomes[0].unpublished().kind shouldBe UnpublishedKind.PERMANENT_FAILURE
                outcomes[1] shouldBe PublishOutcome.Acked
                verify(exactly = 2) { template.send(any<ProducerRecord<String, ByteArray>>()) }
            }
        }
    }

    Given("보내는 순간 예외를 던지는 프로듀서") {
        val template = mockk<KafkaTemplate<String, ByteArray>>()
        every { template.send(any<ProducerRecord<String, ByteArray>>()) } throws IllegalStateException("producer closed")
        val publisher = KafkaMessagePublisher(template)

        When("보내면") {
            val outcome = publisher.publishAll(listOf(message("cmd.point")), Duration.ofSeconds(1)).single()

            Then("Kafka 가 재시도 가능하다고 하지 않은 실패라 영구 실패로 돌려주고 예외를 올리지 않는다") {
                outcome.unpublished().kind shouldBe UnpublishedKind.PERMANENT_FAILURE
                outcome.unpublished().error shouldContain "producer closed"
            }
        }
    }

    Given("브로커 확인이 오지 않는 행 뒤에 또 행이 있는 배치") {
        val template = mockk<KafkaTemplate<String, ByteArray>>()
        every { template.send(any<ProducerRecord<String, ByteArray>>()) } answers {
            Thread.sleep(80)
            CompletableFuture()
        }
        val publisher = KafkaMessagePublisher(template)

        When("배치 마감 50ms 로 보내면") {
            val outcomes = publisher.publishAll(listOf(message("cmd.payment"), message("cmd.payment", key = "11")), Duration.ofMillis(50))

            Then("첫 행은 확인 시간 초과로 재시도 가능한 실패, 마감 뒤의 행은 보내지도 않는다") {
                outcomes shouldContainExactly listOf(
                    PublishOutcome.Unpublished(UnpublishedKind.RETRIABLE_FAILURE, "broker ack timed out"),
                    PublishOutcome.Unpublished(UnpublishedKind.NOT_SENT, "deadline exceeded before send"),
                )
                verify(exactly = 1) { template.send(any<ProducerRecord<String, ByteArray>>()) }
            }
        }
    }

    Given("messageId·sagaId·messageType 헤더를 단 행") {
        val template = mockk<KafkaTemplate<String, ByteArray>>()
        val record = slot<ProducerRecord<String, ByteArray>>()
        every { template.send(capture(record)) } returns acked()
        val publisher = KafkaMessagePublisher(template)

        When("보내면") {
            publisher.publishAll(
                listOf(
                    OutgoingMessage(
                        topic = "saga.replies",
                        key = "10",
                        payload = byteArrayOf(0x0A, 0x02),
                        headers = mapOf("messageId" to "message-1", "sagaId" to "saga-1", "messageType" to "STOCK_BUY"),
                    ),
                ),
                Duration.ofSeconds(1),
            )

            Then("세 헤더가 UTF-8 로, payload 바이트가 그대로 레코드에 실린다") {
                val headers = record.captured.headers().associate { it.key() to it.value().toString(Charsets.UTF_8) }
                headers shouldBe mapOf("messageId" to "message-1", "sagaId" to "saga-1", "messageType" to "STOCK_BUY")
                record.captured.key() shouldBe "10"
                record.captured.value() shouldBe byteArrayOf(0x0A, 0x02)
            }
        }
    }

    Given("늦게 실패로 끝나는 전송") {
        val template = mockk<KafkaTemplate<String, ByteArray>>()
        every { template.send(any<ProducerRecord<String, ByteArray>>()) } answers {
            CompletableFuture.supplyAsync {
                Thread.sleep(50)
                throw KafkaException("delivery timeout", TimeoutException("Expiring 1 record(s)"))
            }
        }
        val publisher = KafkaMessagePublisher(template)

        When("보내면") {
            val outcome = publisher.publishAll(listOf(message("saga.replies")), Duration.ofSeconds(5)).single()

            Then("전달 시간 초과는 재시도 가능한 실패로 원인과 함께 돌려준다") {
                outcome.unpublished().kind shouldBe UnpublishedKind.RETRIABLE_FAILURE
                outcome.unpublished().error shouldContain "Expiring 1 record(s)"
            }
        }
    }

    Given("브로커 연결이 끊겨 늦게 실패하는 전송") {
        val template = mockk<KafkaTemplate<String, ByteArray>>()
        every { template.send(any<ProducerRecord<String, ByteArray>>()) } returns failed(NetworkException("Disconnected from node 1"))
        val publisher = KafkaMessagePublisher(template)

        When("두 토픽으로 보내면") {
            val outcomes = publisher.publishAll(listOf(message("cmd.product"), message("cmd.point")), Duration.ofSeconds(1))

            Then("둘 다 재시도 가능한 실패다") {
                outcomes.kinds() shouldContainExactly List(2) { UnpublishedKind.RETRIABLE_FAILURE }
                outcomes[0].unpublished().error shouldContain "NetworkException"
            }
        }
    }

    Given("행 자체가 받아들여지지 않는 전송 셋") {
        val template = mockk<KafkaTemplate<String, ByteArray>>()
        every { template.send(match<ProducerRecord<String, ByteArray>> { it.topic() == "cmd.product" }) } returns
            failed(RecordTooLargeException("The message is 2000000 bytes"))
        every { template.send(match<ProducerRecord<String, ByteArray>> { it.topic() == "bad topic" }) } returns
            failed(InvalidTopicException("Invalid topics: [bad topic]"))
        every { template.send(match<ProducerRecord<String, ByteArray>> { it.topic() == "cmd.payment" }) } returns
            failed(TopicAuthorizationException("Not authorized to access topics: [cmd.payment]"))
        val publisher = KafkaMessagePublisher(template)

        When("보내면") {
            val outcomes = publisher.publishAll(
                listOf(message("cmd.product"), message("bad topic"), message("cmd.payment")),
                Duration.ofSeconds(1),
            )

            Then("재시도할 수 없는 Kafka 실패라 모두 영구 실패다") {
                outcomes.kinds() shouldContainExactly List(3) { UnpublishedKind.PERMANENT_FAILURE }
                outcomes[0].unpublished().error shouldContain "RecordTooLargeException"
            }
        }
    }

    Given("직렬화에 실패해 보내는 순간 예외를 던지는 프로듀서") {
        val template = mockk<KafkaTemplate<String, ByteArray>>()
        every { template.send(any<ProducerRecord<String, ByteArray>>()) } throws SerializationException("Can't convert value")
        val publisher = KafkaMessagePublisher(template)

        When("보내면") {
            val outcome = publisher.publishAll(listOf(message("cmd.point")), Duration.ofSeconds(1)).single()

            Then("영구 실패다") {
                outcome.unpublished().kind shouldBe UnpublishedKind.PERMANENT_FAILURE
                outcome.unpublished().error shouldContain "SerializationException"
            }
        }
    }

    Given("확인을 기다리는 중에 인터럽트된 스레드") {
        val template = mockk<KafkaTemplate<String, ByteArray>>()
        every { template.send(any<ProducerRecord<String, ByteArray>>()) } answers {
            Thread.currentThread().interrupt()
            CompletableFuture()
        }
        val publisher = KafkaMessagePublisher(template)

        When("보내면") {
            val outcome = publisher.publishAll(listOf(message("cmd.point")), Duration.ofSeconds(1)).single()
            val interrupted = Thread.interrupted()

            Then("재시도 가능한 실패로 돌려주고 인터럽트 표시를 되살린다") {
                outcome shouldBe PublishOutcome.Unpublished(UnpublishedKind.RETRIABLE_FAILURE, "interrupted")
                interrupted shouldBe true
            }
        }
    }
})
