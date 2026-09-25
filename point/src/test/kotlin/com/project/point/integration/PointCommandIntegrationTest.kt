package com.project.point.integration

import com.project.message.point.PointCancelCommand
import com.project.message.point.PointUseCommand
import com.project.message.point.SagaDirection
import com.project.message.point.SagaOutcome
import com.project.message.point.SagaReply
import com.project.message.point.SagaStep
import com.project.point.DbTag
import com.project.point.client.AlertSender
import com.project.point.client.CommandDeadLetterAlert
import com.project.point.client.DeadLetterKind
import com.project.point.fixture.PointFixture
import com.project.point.messaging.MessageHeaders
import com.project.point.repository.PointRepository
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
class PointCommandIntegrationTest : BehaviorSpec() {

    @Autowired
    lateinit var kafka: KafkaContainer

    @Autowired
    lateinit var kafkaTemplate: KafkaTemplate<String, ByteArray>

    @Autowired
    lateinit var pointRepository: PointRepository

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    lateinit var alertSender: AlertSender

    private fun send(orderId: Long, sagaId: String, messageType: String, value: ByteArray) {
        val record = ProducerRecord<String, ByteArray>(IntegrationTestConfig.COMMAND_TOPIC, orderId.toString(), value)
        record.headers().add(MessageHeaders.SAGA_ID, sagaId.toByteArray())
        record.headers().add(MessageHeaders.MESSAGE_TYPE, messageType.toByteArray())
        kafkaTemplate.send(record).get()
    }

    private fun replies(sagaId: String): List<Map<String, Any?>> =
        jdbcTemplate.queryForList(
            "SELECT topic, message_key, message_type, payload, message_id, status, fail_count, occurred_at, published_at FROM outbox WHERE saga_id = ? ORDER BY id",
            sagaId,
        )

    private fun use(sagaId: String, orderId: Long, userId: Long, amount: Long): ByteArray =
        PointUseCommand.newBuilder().setSagaId(sagaId).setOrderId(orderId).setUserId(userId).setAmount(amount).build().toByteArray()

    private fun cancel(sagaId: String, orderId: Long): ByteArray =
        PointCancelCommand.newBuilder().setSagaId(sagaId).setOrderId(orderId).build().toByteArray()

    private fun payloadOf(reply: Map<String, Any?>): SagaReply = SagaReply.parseFrom(reply["payload"] as ByteArray)

    private fun balanceOf(userId: Long): Long? =
        jdbcTemplate.queryForObject("SELECT amount FROM points WHERE user_id = ?", Long::class.java, userId)

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

        Given("잔액 10000인 사용자 7001") {
            val sagaId = UUID.randomUUID().toString()
            pointRepository.save(PointFixture.point(userId = 7001L, amount = 10000L, id = null))

            When("POINT_USE 400 커맨드가 cmd.point 에 도착하면") {
                send(70L, sagaId, "POINT_USE", use(sagaId, 70L, 7001L, 400L))

                Then("잔액이 차감되고 같은 트랜잭션에서 미발행 SUCCEEDED 응답 행이 outbox 에 남는다") {
                    eventually(30.seconds) {
                        balanceOf(7001L) shouldBe 9600L
                        val reply = replies(sagaId).single()
                        reply["topic"] shouldBe "saga.replies"
                        reply["message_key"] shouldBe "70"
                        reply["message_type"] shouldBe "POINT_USE"
                        reply["published_at"] shouldBe null
                        reply["status"] shouldBe "PENDING"
                        reply["fail_count"] shouldBe 0
                        (reply["message_id"] as String).length shouldBe 36
                        reply["occurred_at"].shouldNotBeNull()
                        val payload = payloadOf(reply)
                        payload.outcome shouldBe SagaOutcome.SAGA_OUTCOME_SUCCEEDED
                        payload.step shouldBe SagaStep.SAGA_STEP_POINT
                        payload.direction shouldBe SagaDirection.SAGA_DIRECTION_FORWARD
                    }
                }
            }

            When("같은 sagaId 의 POINT_USE 가 다시 도착하면") {
                send(70L, sagaId, "POINT_USE", use(sagaId, 70L, 7001L, 400L))

                Then("두 번 차감하지 않고 성공 응답을 한 번 더 남긴다") {
                    eventually(30.seconds) {
                        replies(sagaId).size shouldBe 2
                    }
                    balanceOf(7001L) shouldBe 9600L
                }
            }

            When("같은 sagaId 의 POINT_CANCEL 이 도착하면") {
                send(70L, sagaId, "POINT_CANCEL", cancel(sagaId, 70L))

                Then("잔액이 돌아오고 CANCEL 성공 응답이 남는다") {
                    eventually(30.seconds) {
                        balanceOf(7001L) shouldBe 10000L
                        val reply = replies(sagaId).last()
                        reply["message_type"] shouldBe "POINT_CANCEL"
                        val payload = payloadOf(reply)
                        payload.direction shouldBe SagaDirection.SAGA_DIRECTION_CANCEL
                        payload.outcome shouldBe SagaOutcome.SAGA_OUTCOME_SUCCEEDED
                    }
                }
            }
        }

        Given("잔액 100인 사용자 7002") {
            val sagaId = UUID.randomUUID().toString()
            pointRepository.save(PointFixture.point(userId = 7002L, amount = 100L, id = null))

            When("잔액보다 큰 POINT_USE 가 도착하면") {
                send(71L, sagaId, "POINT_USE", use(sagaId, 71L, 7002L, 400L))

                Then("잔액은 그대로이고 롤백과 별도 트랜잭션으로 INSUFFICIENT_POINT 실패 응답 행이 남는다") {
                    eventually(30.seconds) {
                        val reply = replies(sagaId).single()
                        val payload = payloadOf(reply)
                        payload.outcome shouldBe SagaOutcome.SAGA_OUTCOME_FAILED
                        payload.code shouldBe "INSUFFICIENT_POINT"
                    }
                    balanceOf(7002L) shouldBe 100L
                }
            }
        }

        Given("모양이 맞지 않는 커맨드") {
            val sagaId = UUID.randomUUID().toString()

            When("깨진 Protobuf 바이트가 cmd.point 에 도착하면") {
                send(72L, sagaId, "POINT_USE", BROKEN_BYTES)

                Then("재시도 토픽을 거치지 않고 cmd.point-dlt 로 가서 INTERNAL_ERROR 실패 응답과 POISON 알림을 남긴다") {
                    consumer(IntegrationTestConfig.DLT_TOPIC).use { dlt ->
                        val received = mutableListOf<ConsumerRecord<String, ByteArray>>()
                        eventually(20.seconds) {
                            received += dlt.poll(Duration.ofMillis(500))
                            val record = received.single { it.header(MessageHeaders.SAGA_ID) == sagaId }
                            record.key() shouldBe "72"
                            record.value().toList() shouldBe BROKEN_BYTES.toList()
                        }
                    }
                    eventually(10.seconds) {
                        verify(exactly = 1) {
                            alertSender.send(
                                match<CommandDeadLetterAlert> {
                                    it.sagaId == sagaId && it.orderId == "72" && it.messageType == "POINT_USE" &&
                                        it.kind == DeadLetterKind.POISON && it.failedReplyWritten
                                },
                            )
                        }
                    }
                    consumer(IntegrationTestConfig.RETRY_0_TOPIC).use { retry ->
                        val retried = (1..4).flatMap { retry.poll(Duration.ofMillis(500)) }
                        retried.filter { it.header(MessageHeaders.SAGA_ID) == sagaId }.shouldBeEmpty()
                    }
                    val reply = replies(sagaId).single()
                    val payload = payloadOf(reply)
                    reply["topic"] shouldBe "saga.replies"
                    reply["message_key"] shouldBe "72"
                    reply["message_type"] shouldBe "POINT_USE"
                    reply["status"] shouldBe "PENDING"
                    payload.sagaId shouldBe sagaId
                    payload.orderId shouldBe 72L
                    payload.step shouldBe SagaStep.SAGA_STEP_POINT
                    payload.direction shouldBe SagaDirection.SAGA_DIRECTION_FORWARD
                    payload.outcome shouldBe SagaOutcome.SAGA_OUTCOME_FAILED
                    payload.code shouldBe "INTERNAL_ERROR"
                }
            }
        }
    }

    companion object {
        private val BROKEN_BYTES = byteArrayOf(0x0a, 0x05, 0x73)
    }
}
