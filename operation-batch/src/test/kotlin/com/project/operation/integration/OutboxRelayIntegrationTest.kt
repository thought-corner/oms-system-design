package com.project.operation.integration

import com.project.operation.DbTag
import com.project.operation.client.AlertSender
import com.project.operation.client.OutboxBacklogAlert
import com.project.operation.client.OutboxPublishFailedAlert
import com.project.operation.domain.OutboxSource
import com.project.operation.service.OutboxRelay
import com.project.operation.service.OutboxService
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.verify
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.apache.kafka.common.serialization.StringDeserializer
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.kafka.KafkaContainer
import org.testcontainers.mysql.MySQLContainer
import java.time.Clock
import java.time.Duration
import java.time.LocalDateTime
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

@SpringBootTest
@Import(IntegrationTestConfig::class)
class OutboxRelayIntegrationTest : BehaviorSpec() {

    @Autowired
    lateinit var mysql: MySQLContainer

    @Autowired
    lateinit var kafka: KafkaContainer

    @Autowired
    lateinit var outboxRelay: OutboxRelay

    @Autowired
    lateinit var outboxService: OutboxService

    @Autowired
    lateinit var alertSender: AlertSender

    @Autowired
    lateinit var clock: Clock

    @Autowired
    lateinit var operationJdbc: JdbcTemplate

    private val root: JdbcTemplate by lazy {
        JdbcTemplate(DriverManagerDataSource(mysql.jdbcUrl, "root", IntegrationTestConfig.PASSWORD))
    }

    private fun insert(
        source: OutboxSource,
        topic: String,
        sagaId: String,
        occurredAt: LocalDateTime = LocalDateTime.now(clock),
        status: String = "PENDING",
        failCount: Int = 0,
        held: Boolean = false,
    ): String {
        val messageId = UUID.randomUUID().toString()
        val claimedAt = if (held) "NOW(6) + INTERVAL 1 DAY" else "NULL"
        root.update(
            "INSERT INTO ${source.table} (message_id, topic, message_key, saga_id, message_type, payload, status, fail_count, occurred_at, claimed_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, $claimedAt)",
            messageId,
            topic,
            "10",
            sagaId,
            "STOCK_BUY",
            payloadOf(sagaId),
            status,
            failCount,
            occurredAt,
        )
        return messageId
    }

    private fun payloadOf(sagaId: String): ByteArray =
        byteArrayOf(0x0A, sagaId.length.toByte()) + sagaId.toByteArray(Charsets.UTF_8)

    private fun releaseTogether(source: OutboxSource, vararg sagaIds: String) {
        root.update(
            "UPDATE ${source.table} SET claimed_at = NULL WHERE saga_id IN (${sagaIds.joinToString { "?" }})",
            *sagaIds,
        )
    }

    private fun row(source: OutboxSource, sagaId: String): Map<String, Any?> =
        root.queryForMap("SELECT * FROM ${source.table} WHERE saga_id = ?", sagaId)

    private fun publishedAt(source: OutboxSource, sagaId: String): LocalDateTime? =
        root.queryForObject("SELECT published_at FROM ${source.table} WHERE saga_id = ?", LocalDateTime::class.java, sagaId)

    private fun claimedAt(source: OutboxSource, sagaId: String): LocalDateTime? =
        root.queryForObject("SELECT claimed_at FROM ${source.table} WHERE saga_id = ?", LocalDateTime::class.java, sagaId)

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

        Given("order·payment 스키마의 outbox 에 서비스가 넣은 미발행 행") {
            val orderSaga = UUID.randomUUID().toString()
            val paymentSaga = UUID.randomUUID().toString()

            When("릴레이가 폴링하면") {
                insert(OutboxSource.ORDER, IntegrationTestConfig.COMMAND_TOPIC, orderSaga)
                val paymentMessageId = insert(OutboxSource.PAYMENT, IntegrationTestConfig.REPLY_TOPIC, paymentSaga)

                Then("브로커 확인 뒤 PUBLISHED 가 되고 published_at 이 채워진다") {
                    eventually(20.seconds) {
                        publishedAt(OutboxSource.ORDER, orderSaga).shouldNotBeNull()
                        publishedAt(OutboxSource.PAYMENT, paymentSaga).shouldNotBeNull()
                    }
                    row(OutboxSource.ORDER, orderSaga)["status"] shouldBe "PUBLISHED"
                    row(OutboxSource.PAYMENT, paymentSaga)["status"] shouldBe "PUBLISHED"
                    row(OutboxSource.PAYMENT, paymentSaga)["fail_count"] shouldBe 0
                }

                Then("레코드는 키 orderId, 값 payload, 헤더 messageId·sagaId·messageType 으로 도착한다") {
                    consumer(IntegrationTestConfig.REPLY_TOPIC).use { consumer ->
                        val received = mutableListOf<ConsumerRecord<String, ByteArray>>()
                        eventually(20.seconds) {
                            received += consumer.poll(Duration.ofMillis(500))
                            val record = received.single { it.header("sagaId") == paymentSaga }
                            record.key() shouldBe "10"
                            record.value() shouldBe payloadOf(paymentSaga)
                            record.header("messageType") shouldBe "STOCK_BUY"
                            record.header("messageId") shouldBe paymentMessageId
                        }
                    }
                }
            }
        }

        Given("다른 릴레이가 방금 집어 간(임대 중인) 행") {
            val sagaId = UUID.randomUUID().toString()
            insert(OutboxSource.PRODUCT, IntegrationTestConfig.REPLY_TOPIC, sagaId, held = true)

            When("임대가 끝나지 않은 동안") {
                Thread.sleep(1500)

                Then("다시 집지 않는다") {
                    publishedAt(OutboxSource.PRODUCT, sagaId).shouldBeNull()
                }
            }

            When("임대가 30초를 넘기면") {
                root.update("UPDATE ${OutboxSource.PRODUCT.table} SET claimed_at = NOW(6) - INTERVAL 31 SECOND WHERE saga_id = ?", sagaId)

                Then("다시 집어 발행한다") {
                    eventually(20.seconds) {
                        publishedAt(OutboxSource.PRODUCT, sagaId).shouldNotBeNull()
                    }
                }
            }
        }

        Given("같은 스키마에 없는 토픽으로 가는 10분 묵은 행과 정상 행") {
            val broken = UUID.randomUUID().toString()
            val healthy = UUID.randomUUID().toString()
            insert(OutboxSource.POINT, "no.such.topic", broken, LocalDateTime.now(clock).minusMinutes(10), held = true)
            insert(OutboxSource.POINT, IntegrationTestConfig.REPLY_TOPIC, healthy, held = true)

            When("릴레이가 두 행을 한 배치로 폴링하면") {
                releaseTogether(OutboxSource.POINT, broken, healthy)

                Then("정상 행은 발행되고, 브로커가 닿는 배치라 깨진 행은 실패가 세어진 채 PENDING 으로 남아 임대 뒤 다시 시도된다") {
                    eventually(30.seconds) {
                        publishedAt(OutboxSource.POINT, healthy).shouldNotBeNull()
                        claimedAt(OutboxSource.POINT, broken).shouldNotBeNull()
                        (row(OutboxSource.POINT, broken)["fail_count"] as Int) shouldBeGreaterThanOrEqual 1
                    }
                    val brokenRow = row(OutboxSource.POINT, broken)
                    brokenRow["status"] shouldBe "PENDING"
                    brokenRow["published_at"].shouldBeNull()
                    brokenRow["failed_at"].shouldNotBeNull()
                    (brokenRow["last_error"] as String).length shouldBeLessThanOrEqual 255
                }
            }

            When("관측이 돌면") {
                outboxRelay.watchBacklog()

                Then("5분 넘게 밀린 point 스키마를 알린다") {
                    verify(atLeast = 1) {
                        alertSender.send(match<OutboxBacklogAlert> { it.schema == "point" && it.ageSeconds >= 600 })
                    }
                }
            }

            root.update("DELETE FROM ${OutboxSource.POINT.table} WHERE saga_id = ?", broken)
        }

        Given("없는 토픽으로 가는, 이미 네 번 실패한 행과 같은 스키마의 정상 행") {
            val sagaId = UUID.randomUUID().toString()
            val healthy = UUID.randomUUID().toString()
            val messageId = insert(OutboxSource.PRODUCT, "no.such.topic", sagaId, failCount = 4, held = true)
            insert(OutboxSource.PRODUCT, IntegrationTestConfig.REPLY_TOPIC, healthy, held = true)

            When("릴레이가 두 행을 한 배치로 다섯 번째로 시도하면") {
                releaseTogether(OutboxSource.PRODUCT, sagaId, healthy)

                Then("정상 행이 확인받은 배치라 깨진 행은 FAILED 로 바뀌고 운영자 알림이 한 번 나간다") {
                    eventually(30.seconds) {
                        publishedAt(OutboxSource.PRODUCT, healthy).shouldNotBeNull()
                        row(OutboxSource.PRODUCT, sagaId)["status"] shouldBe "FAILED"
                    }
                    val failedRow = row(OutboxSource.PRODUCT, sagaId)
                    failedRow["fail_count"] shouldBe 5
                    failedRow["failed_at"].shouldNotBeNull()
                    failedRow["last_error"].shouldNotBeNull()
                    failedRow["published_at"].shouldBeNull()
                    verify(exactly = 1) {
                        alertSender.send(match<OutboxPublishFailedAlert> { it.messageId == messageId && it.schema == "product" && it.failCount == 5 })
                    }
                }
            }

            When("임대가 끝난 뒤에도") {
                root.update("UPDATE ${OutboxSource.PRODUCT.table} SET claimed_at = NOW(6) - INTERVAL 1 MINUTE WHERE saga_id = ?", sagaId)
                Thread.sleep(1500)

                Then("FAILED 행은 다시 집지 않아 실패가 더 세어지지 않고 알림도 더 나가지 않는다") {
                    row(OutboxSource.PRODUCT, sagaId)["fail_count"] shouldBe 5
                    verify(exactly = 1) { alertSender.send(match<OutboxPublishFailedAlert> { it.messageId == messageId }) }
                }
            }

            When("관측이 돌면") {
                val backlog = outboxService.backlogOf(OutboxSource.PRODUCT)

                Then("FAILED 행 수가 보고된다") {
                    backlog.failed shouldBeGreaterThanOrEqual 1L
                }
            }

            root.update("DELETE FROM ${OutboxSource.PRODUCT.table} WHERE saga_id = ?", sagaId)
        }

        Given("발행한 지 8일 된 PUBLISHED 행, 1일 된 PUBLISHED 행, 8일 전에 FAILED 가 된 행") {
            val old = UUID.randomUUID().toString()
            val recent = UUID.randomUUID().toString()
            val failed = UUID.randomUUID().toString()
            val eightDaysAgo = LocalDateTime.now(clock).minusDays(8)
            insert(OutboxSource.ORDER, IntegrationTestConfig.REPLY_TOPIC, old, occurredAt = eightDaysAgo, status = "PUBLISHED")
            insert(OutboxSource.ORDER, IntegrationTestConfig.REPLY_TOPIC, recent, status = "PUBLISHED")
            insert(OutboxSource.ORDER, "no.such.topic", failed, occurredAt = eightDaysAgo, status = "FAILED", failCount = 5)
            root.update("UPDATE ${OutboxSource.ORDER.table} SET published_at = NOW(6) - INTERVAL 8 DAY WHERE saga_id = ?", old)
            root.update("UPDATE ${OutboxSource.ORDER.table} SET published_at = NOW(6) - INTERVAL 1 DAY WHERE saga_id = ?", recent)
            root.update("UPDATE ${OutboxSource.ORDER.table} SET failed_at = NOW(6) - INTERVAL 8 DAY WHERE saga_id = ?", failed)

            When("정리가 돌면") {
                outboxRelay.purge()

                Then("7일 지난 PUBLISHED 행만 지우고 FAILED 행은 사람이 볼 때까지 남긴다") {
                    root.queryForList("SELECT id FROM ${OutboxSource.ORDER.table} WHERE saga_id = ?", old).shouldBeEmpty()
                    publishedAt(OutboxSource.ORDER, recent) shouldNotBe null
                    row(OutboxSource.ORDER, failed)["status"] shouldBe "FAILED"
                }
            }

            root.update("DELETE FROM ${OutboxSource.ORDER.table} WHERE saga_id = ?", failed)
        }

        Given("operation_user 계정") {

            Then("outbox 에 행을 넣거나 서비스의 다른 테이블을 읽을 수 없다") {
                val insertDenied = runCatching {
                    operationJdbc.update(
                        "INSERT INTO ${OutboxSource.ORDER.table} (message_id, topic, message_key, saga_id, message_type, payload, status, occurred_at) VALUES ('i', 't', 'k', 's', 'm', ?, 'PENDING', NOW(6))",
                        byteArrayOf(0x0A),
                    )
                }.exceptionOrNull()
                val readDenied = runCatching { operationJdbc.queryForList("SELECT 1 FROM `order`.`order_saga`") }.exceptionOrNull()
                (insertDenied is DataAccessException) shouldBe true
                (readDenied is DataAccessException) shouldBe true
            }

            Then("미발행 적체는 서비스 계정 없이 조회된다") {
                val backlog = outboxService.backlogOf(OutboxSource.PAYMENT)
                backlog.pending shouldBe 0L
                backlog.failed shouldBe 0L
            }
        }
    }
}
