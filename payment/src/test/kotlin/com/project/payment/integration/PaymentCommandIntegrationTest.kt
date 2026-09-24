package com.project.payment.integration

import com.project.payment.DbTag
import com.project.payment.client.AlertSender
import com.project.payment.client.DeadLetterAlert
import com.project.payment.client.DeadLetterKind
import com.project.payment.domain.OutboxMessage
import com.project.payment.domain.OutboxStatus
import com.project.payment.domain.PaymentStatus
import com.project.payment.repository.OutboxMessageRepository
import com.project.payment.repository.PaymentRepository
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.verify
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.header.internals.RecordHeader
import org.apache.kafka.common.serialization.StringDeserializer
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.kafka.core.KafkaTemplate
import org.testcontainers.kafka.KafkaContainer
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

@SpringBootTest
@Import(IntegrationTestConfig::class)
class PaymentCommandIntegrationTest : BehaviorSpec() {

    @Autowired
    lateinit var kafka: KafkaContainer

    @Autowired
    lateinit var kafkaTemplate: KafkaTemplate<String, String>

    @Autowired
    lateinit var paymentRepository: PaymentRepository

    @Autowired
    lateinit var outboxMessageRepository: OutboxMessageRepository

    @Autowired
    lateinit var alertSender: AlertSender

    @Autowired
    lateinit var jsonMapper: JsonMapper

    private fun send(orderId: Long, sagaId: String, messageType: String, payload: String) {
        val record = ProducerRecord<String, String>(IntegrationTestConfig.COMMAND_TOPIC, orderId.toString(), payload)
        record.headers().add(RecordHeader("sagaId", sagaId.toByteArray()))
        record.headers().add(RecordHeader("messageType", messageType.toByteArray()))
        kafkaTemplate.send(record).get()
    }

    private fun pay(orderId: Long, sagaId: String) =
        send(orderId, sagaId, "PAYMENT_PAY", """{"sagaId":"$sagaId","orderId":$orderId,"userId":1,"amount":400}""")

    private fun replyOf(sagaId: String, messageType: String): OutboxMessage? =
        outboxMessageRepository.findAllBySagaId(sagaId).singleOrNull { it.messageType == messageType }

    private fun OutboxMessage.body(): JsonNode = jsonMapper.readTree(payload)

    private fun records(topic: String, sagaId: String, wait: Duration): List<ConsumerRecord<String, String>> =
        KafkaConsumer<String, String>(
            mapOf(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to kafka.bootstrapServers,
                ConsumerConfig.GROUP_ID_CONFIG to "it-${UUID.randomUUID()}",
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java,
            ),
        ).use { consumer ->
            consumer.subscribe(listOf(topic))
            val deadline = System.nanoTime() + wait.toNanos()
            val received = mutableListOf<ConsumerRecord<String, String>>()
            while (System.nanoTime() < deadline) {
                received += consumer.poll(Duration.ofMillis(500))
            }
            received.filter { it.headers().lastHeader("sagaId")?.value()?.toString(Charsets.UTF_8) == sagaId }
        }

    init {
        extensions(SpringExtension())
        tags(DbTag)

        Given("결제 이력이 없는 주문 501") {
            val orderId = 501L
            val sagaId = UUID.randomUUID().toString()

            When("cmd.payment 에 PAYMENT_PAY 커맨드가 오면") {
                pay(orderId, sagaId)

                Then("PAID 결제 1건과 같은 트랜잭션의 SUCCEEDED 응답 outbox 행이 남는다") {
                    eventually(30.seconds) {
                        val payment = paymentRepository.findBySagaId(sagaId).shouldNotBeNull()
                        payment.status shouldBe PaymentStatus.PAID
                        val reply = replyOf(sagaId, "PAYMENT_PAY").shouldNotBeNull()
                        reply.topic shouldBe "saga.replies"
                        reply.messageKey shouldBe orderId.toString()
                        reply.status shouldBe OutboxStatus.PENDING
                        reply.failCount shouldBe 0
                        reply.messageId.length shouldBe 36
                        reply.body().get("outcome").asString() shouldBe "SUCCEEDED"
                        reply.body().get("result").get("paymentId").asLong() shouldBe payment.id
                    }
                }
            }

            When("다른 사가가 같은 주문으로 PAYMENT_PAY 를 보내면") {
                val otherSagaId = UUID.randomUUID().toString()
                pay(orderId, otherSagaId)

                Then("결제는 늘지 않고 롤백과 분리된 트랜잭션에서 ALREADY_PAID 실패 응답이 남는다") {
                    eventually(30.seconds) {
                        val reply = replyOf(otherSagaId, "PAYMENT_PAY").shouldNotBeNull()
                        reply.body().get("outcome").asString() shouldBe "FAILED"
                        reply.body().get("code").asString() shouldBe "ALREADY_PAID"
                    }
                    paymentRepository.findBySagaId(otherSagaId) shouldBe null
                }
            }

            When("같은 사가의 PAYMENT_CANCEL 이 오면") {
                send(orderId, sagaId, "PAYMENT_CANCEL", """{"sagaId":"$sagaId","orderId":$orderId}""")

                Then("결제가 CANCELED 가 되고 빈 결과의 SUCCEEDED 응답이 남는다") {
                    eventually(30.seconds) {
                        paymentRepository.findBySagaId(sagaId)?.status shouldBe PaymentStatus.CANCELED
                        val reply = replyOf(sagaId, "PAYMENT_CANCEL").shouldNotBeNull()
                        reply.body().get("direction").asString() shouldBe "CANCEL"
                        reply.body().get("outcome").asString() shouldBe "SUCCEEDED"
                    }
                }
            }
        }

        Given("JSON 이 깨진 커맨드") {
            val sagaId = UUID.randomUUID().toString()

            When("cmd.payment 에 들어오면") {
                send(502L, sagaId, "PAYMENT_PAY", "{not json")

                Then("재시도 토픽을 거치지 않고 cmd.payment-dlt 로 가서 INTERNAL_ERROR 실패 응답과 POISON 알림을 남긴다") {
                    val dead = records(IntegrationTestConfig.DLT_TOPIC, sagaId, Duration.ofSeconds(10))
                    dead.size shouldBe 1
                    dead.single().key() shouldBe "502"
                    IntegrationTestConfig.RETRY_TOPICS.forEach { topic ->
                        records(topic, sagaId, Duration.ofSeconds(2)).shouldBeEmpty()
                    }
                    eventually(10.seconds) {
                        verify(atLeast = 1) {
                            alertSender.send(
                                match<DeadLetterAlert> {
                                    it.sagaId == sagaId && it.orderId == "502" && it.kind == DeadLetterKind.POISON && it.failedReplyWritten
                                },
                            )
                        }
                    }
                    val reply = outboxMessageRepository.findAllBySagaId(sagaId).single()
                    reply.topic shouldBe "saga.replies"
                    reply.messageKey shouldBe "502"
                    reply.messageType shouldBe "PAYMENT_PAY"
                    reply.status shouldBe OutboxStatus.PENDING
                    reply.body().get("sagaId").asString() shouldBe sagaId
                    reply.body().get("orderId").asLong() shouldBe 502L
                    reply.body().get("step").asString() shouldBe "PAYMENT"
                    reply.body().get("direction").asString() shouldBe "FORWARD"
                    reply.body().get("outcome").asString() shouldBe "FAILED"
                    reply.body().get("code").asString() shouldBe "INTERNAL_ERROR"
                }
            }
        }
    }
}
