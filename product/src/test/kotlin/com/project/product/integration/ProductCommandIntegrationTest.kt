package com.project.product.integration

import com.project.message.product.SagaDirection
import com.project.message.product.SagaOutcome
import com.project.message.product.SagaReply
import com.project.message.product.SagaStep
import com.project.message.product.StockBuyCommand
import com.project.product.DbTag
import com.project.product.client.AlertSender
import com.project.product.client.DeadLetterAlert
import com.project.product.client.DeadLetterKind
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.mockk.slot
import io.mockk.verify
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.header.internals.RecordHeader
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.apache.kafka.common.serialization.StringDeserializer
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.kafka.core.KafkaTemplate
import org.testcontainers.kafka.KafkaContainer
import java.time.Duration
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

@SpringBootTest
@Import(IntegrationTestConfig::class)
class ProductCommandIntegrationTest : BehaviorSpec() {

    @Autowired
    lateinit var kafka: KafkaContainer

    @Autowired
    lateinit var kafkaTemplate: KafkaTemplate<String, ByteArray>

    @Autowired
    lateinit var jdbc: JdbcTemplate

    @Autowired
    lateinit var alertSender: AlertSender

    private fun publish(orderId: Long, sagaId: String, messageType: String, value: ByteArray) {
        val record = ProducerRecord<String, ByteArray>(IntegrationTestConfig.COMMAND_TOPIC, orderId.toString(), value)
        record.headers().add(RecordHeader("sagaId", sagaId.toByteArray()))
        record.headers().add(RecordHeader("messageType", messageType.toByteArray()))
        kafkaTemplate.send(record).get()
    }

    private fun replies(sagaId: String): List<Map<String, Any?>> =
        jdbc.queryForList("SELECT topic, message_key, message_type, payload, message_id, status, fail_count, occurred_at, published_at FROM outbox WHERE saga_id = ?", sagaId)

    private fun buyCommand(sagaId: String, orderId: Long, productId: Long, quantity: Long): ByteArray =
        StockBuyCommand.newBuilder()
            .setSagaId(sagaId)
            .setOrderId(orderId)
            .addItems(StockBuyCommand.Item.newBuilder().setProductId(productId).setQuantity(quantity))
            .build()
            .toByteArray()

    private fun payloadOf(reply: Map<String, Any?>): SagaReply = SagaReply.parseFrom(reply["payload"] as ByteArray)

    private fun quantityOf(productId: Long): Long =
        requireNotNull(jdbc.queryForObject("SELECT quantity FROM products WHERE id = ?", Long::class.java, productId))

    private fun consumer(topic: String): KafkaConsumer<String, ByteArray> =
        KafkaConsumer<String, ByteArray>(
            mapOf(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to kafka.bootstrapServers,
                ConsumerConfig.GROUP_ID_CONFIG to "it-${UUID.randomUUID()}",
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG to ByteArrayDeserializer::class.java,
            ),
        ).also { it.subscribe(listOf(topic)) }

    private fun ConsumerRecord<String, ByteArray>.header(name: String): String? =
        headers().lastHeader(name)?.value()?.toString(Charsets.UTF_8)

    init {
        extensions(SpringExtension())
        tags(DbTag)

        Given("재고 100 · 단가 100인 상품 1") {
            val sagaId = UUID.randomUUID().toString()
            val before = quantityOf(1L)

            When("cmd.product 에 상품 1을 3개 사는 STOCK_BUY 가 오면") {
                publish(2001L, sagaId, "STOCK_BUY", buyCommand(sagaId, 2001L, 1L, 3L))

                Then("재고 차감과 같은 트랜잭션으로 saga.replies 행 하나를 outbox 에 남긴다") {
                    eventually(30.seconds) {
                        replies(sagaId).size shouldBe 1
                    }
                    val reply = replies(sagaId).single()
                    val payload = payloadOf(reply)
                    reply["topic"] shouldBe "saga.replies"
                    reply["message_key"] shouldBe "2001"
                    reply["message_type"] shouldBe "STOCK_BUY"
                    reply["published_at"] shouldBe null
                    reply["status"] shouldBe "PENDING"
                    reply["fail_count"] shouldBe 0
                    (reply["message_id"] as String).length shouldBe 36
                    reply["occurred_at"].shouldNotBeNull()
                    payload.outcome shouldBe SagaOutcome.SAGA_OUTCOME_SUCCEEDED
                    payload.step shouldBe SagaStep.SAGA_STEP_STOCK
                    payload.direction shouldBe SagaDirection.SAGA_DIRECTION_FORWARD
                    payload.totalPrice shouldBe 300L
                    quantityOf(1L) shouldBe before - 3L
                }
            }
        }

        Given("재고 100인 상품 2") {
            val sagaId = UUID.randomUUID().toString()
            val before = quantityOf(2L)

            When("1000개를 사는 STOCK_BUY 가 오면") {
                publish(2002L, sagaId, "STOCK_BUY", buyCommand(sagaId, 2002L, 2L, 1000L))

                Then("차감은 롤백되고 별도 트랜잭션의 INSUFFICIENT_STOCK 실패 응답만 남는다") {
                    eventually(30.seconds) {
                        replies(sagaId).size shouldBe 1
                    }
                    val payload = payloadOf(replies(sagaId).single())
                    payload.outcome shouldBe SagaOutcome.SAGA_OUTCOME_FAILED
                    payload.code shouldBe "INSUFFICIENT_STOCK"
                    quantityOf(2L) shouldBe before
                    jdbc.queryForList("SELECT id FROM product_transaction_histories WHERE saga_id = ?", sagaId).shouldBeEmpty()
                }
            }
        }

        Given("Protobuf 로 읽을 수 없는 커맨드") {
            val sagaId = UUID.randomUUID().toString()
            val broken = byteArrayOf(0x0A, 0x05, 0x73)

            When("cmd.product 에 오면") {
                publish(2003L, sagaId, "STOCK_BUY", broken)

                Then("재시도 토픽을 거치지 않고 cmd.product-dlt 로 가서 INTERNAL_ERROR 실패 응답과 POISON 알림을 남긴다") {
                    consumer(IntegrationTestConfig.DLT_TOPIC).use { dlt ->
                        val received = mutableListOf<ConsumerRecord<String, ByteArray>>()
                        eventually(30.seconds) {
                            received += dlt.poll(Duration.ofMillis(500))
                            val record = received.single { it.header("sagaId") == sagaId }
                            record.key() shouldBe "2003"
                            record.value() shouldBe broken
                        }
                    }
                    val alert = slot<DeadLetterAlert>()
                    eventually(10.seconds) {
                        verify { alertSender.send(match { it.sagaId == sagaId }) }
                    }
                    verify { alertSender.send(capture(alert)) }
                    alert.captured.topic shouldBe IntegrationTestConfig.COMMAND_TOPIC
                    alert.captured.sagaId shouldBe sagaId
                    alert.captured.orderId shouldBe "2003"
                    alert.captured.messageType shouldBe "STOCK_BUY"
                    alert.captured.exceptionClass.shouldNotBeNull() shouldStartWith "com.google.protobuf"
                    alert.captured.kind shouldBe DeadLetterKind.POISON
                    alert.captured.failedReplyWritten shouldBe true
                    consumer(IntegrationTestConfig.RETRY_TOPIC_0).use { retry ->
                        val retried = (1..6).flatMap { retry.poll(Duration.ofMillis(500)) }
                        retried.filter { it.header("sagaId") == sagaId }.shouldBeEmpty()
                    }
                    val reply = replies(sagaId).single()
                    val payload = payloadOf(reply)
                    reply["topic"] shouldBe "saga.replies"
                    reply["message_key"] shouldBe "2003"
                    reply["message_type"] shouldBe "STOCK_BUY"
                    reply["status"] shouldBe "PENDING"
                    payload.sagaId shouldBe sagaId
                    payload.orderId shouldBe 2003L
                    payload.step shouldBe SagaStep.SAGA_STEP_STOCK
                    payload.direction shouldBe SagaDirection.SAGA_DIRECTION_FORWARD
                    payload.outcome shouldBe SagaOutcome.SAGA_OUTCOME_FAILED
                    payload.code shouldBe "INTERNAL_ERROR"
                }
            }
        }
    }
}
