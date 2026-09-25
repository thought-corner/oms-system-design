package com.project.operation.integration

import com.project.operation.DbTag
import com.project.operation.client.AlertSender
import com.project.operation.client.OutboxDelayAlert
import com.project.operation.client.OutboxPublishFailedAlert
import com.project.operation.domain.OutboxFailure
import com.project.operation.domain.OutboxSource
import com.project.operation.domain.PublishFailure
import com.project.operation.repository.OutboxRepository
import com.project.operation.service.worker.OutboxDelayMonitor
import com.project.operation.service.worker.PublishedOutboxCleaner
import com.project.operation.service.OutboxService
import com.project.operation.service.policy.PublishedOutboxRetentionPolicy
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
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
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
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
    lateinit var publishedOutboxCleaner: PublishedOutboxCleaner

    @Autowired
    lateinit var outboxDelayMonitor: OutboxDelayMonitor

    @Autowired
    lateinit var outboxService: OutboxService

    @Autowired
    lateinit var outboxRepository: OutboxRepository

    @Autowired
    lateinit var transactionManager: PlatformTransactionManager

    @Autowired
    lateinit var alertSender: AlertSender

    @Autowired
    lateinit var clock: Clock

    @Autowired
    lateinit var operationJdbc: JdbcTemplate

    private val tables: OutboxTables by lazy { OutboxTables(mysql) }

    private fun now(): LocalDateTime = LocalDateTime.now(clock)

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
                tables.insert(OutboxSource.ORDER, IntegrationTestConfig.COMMAND_TOPIC, orderSaga, now())
                val paymentMessageId = tables.insert(OutboxSource.PAYMENT, IntegrationTestConfig.REPLY_TOPIC, paymentSaga, now())

                Then("브로커 확인 뒤 PUBLISHED 가 되고 published_at 이 채워진다") {
                    eventually(20.seconds) {
                        tables.publishedAt(OutboxSource.ORDER, orderSaga).shouldNotBeNull()
                        tables.publishedAt(OutboxSource.PAYMENT, paymentSaga).shouldNotBeNull()
                    }
                    tables.row(OutboxSource.ORDER, orderSaga)["status"] shouldBe "PUBLISHED"
                    tables.row(OutboxSource.PAYMENT, paymentSaga)["status"] shouldBe "PUBLISHED"
                    tables.row(OutboxSource.PAYMENT, paymentSaga)["fail_count"] shouldBe 0
                }

                Then("레코드는 키 orderId, 값 payload, 헤더 messageId·sagaId·messageType 으로 도착한다") {
                    consumer(IntegrationTestConfig.REPLY_TOPIC).use { consumer ->
                        val received = mutableListOf<ConsumerRecord<String, ByteArray>>()
                        eventually(20.seconds) {
                            received += consumer.poll(Duration.ofMillis(500))
                            val record = received.single { it.header("sagaId") == paymentSaga }
                            record.key() shouldBe "10"
                            record.value() shouldBe OutboxTables.payloadOf(paymentSaga)
                            record.header("messageType") shouldBe "STOCK_BUY"
                            record.header("messageId") shouldBe paymentMessageId
                        }
                    }
                }
            }
        }

        Given("다른 릴레이가 방금 집어 간(임대 중인) 행") {
            val sagaId = UUID.randomUUID().toString()
            tables.insert(OutboxSource.PRODUCT, IntegrationTestConfig.REPLY_TOPIC, sagaId, now(), held = true)

            When("임대가 끝나지 않은 동안") {
                Thread.sleep(1500)

                Then("다시 집지 않는다") {
                    tables.publishedAt(OutboxSource.PRODUCT, sagaId).shouldBeNull()
                }
            }

            When("임대가 30초를 넘기면") {
                tables.claimedSecondsAgo(OutboxSource.PRODUCT, sagaId, 31)

                Then("다시 집어 발행한다") {
                    eventually(20.seconds) {
                        tables.publishedAt(OutboxSource.PRODUCT, sagaId).shouldNotBeNull()
                    }
                }
            }
        }

        Given("같은 스키마에 없는 토픽으로 가는 10분 묵은 행과 정상 행") {
            val broken = UUID.randomUUID().toString()
            val healthy = UUID.randomUUID().toString()
            tables.insert(OutboxSource.POINT, "no.such.topic", broken, now().minusMinutes(10), held = true)
            tables.insert(OutboxSource.POINT, IntegrationTestConfig.REPLY_TOPIC, healthy, now(), held = true)

            When("릴레이가 두 행을 한 배치로 폴링하면") {
                tables.release(OutboxSource.POINT, listOf(broken, healthy))

                Then("정상 행은 발행되고, 브로커가 닿는 배치라 깨진 행은 실패가 세어진 채 PENDING 으로 남아 임대 뒤 다시 시도된다") {
                    eventually(30.seconds) {
                        tables.publishedAt(OutboxSource.POINT, healthy).shouldNotBeNull()
                        tables.claimedAt(OutboxSource.POINT, broken).shouldNotBeNull()
                        (tables.row(OutboxSource.POINT, broken)["fail_count"] as Int) shouldBeGreaterThanOrEqual 1
                    }
                    val brokenRow = tables.row(OutboxSource.POINT, broken)
                    brokenRow["status"] shouldBe "PENDING"
                    brokenRow["published_at"].shouldBeNull()
                    brokenRow["failed_at"].shouldNotBeNull()
                    (brokenRow["last_error"] as String).length shouldBeLessThanOrEqual 255
                }
            }

            When("관측이 돌면") {
                outboxDelayMonitor.checkDelays()

                Then("5분 넘게 밀린 point 스키마를 알린다") {
                    verify(atLeast = 1) {
                        alertSender.send(match<OutboxDelayAlert> { it.schema == "point" && it.ageSeconds >= 600 })
                    }
                }
            }

            tables.delete(OutboxSource.POINT, listOf(broken))
        }

        Given("없는 토픽으로 가는, 이미 네 번 실패한 행과 같은 스키마의 정상 행") {
            val sagaId = UUID.randomUUID().toString()
            val healthy = UUID.randomUUID().toString()
            val messageId = tables.insert(OutboxSource.PRODUCT, "no.such.topic", sagaId, now(), failCount = 4, held = true)
            tables.insert(OutboxSource.PRODUCT, IntegrationTestConfig.REPLY_TOPIC, healthy, now(), held = true)

            When("릴레이가 두 행을 한 배치로 다섯 번째로 시도하면") {
                tables.release(OutboxSource.PRODUCT, listOf(sagaId, healthy))

                Then("정상 행이 확인받은 배치라 깨진 행은 FAILED 로 바뀌고 운영자 알림이 한 번 나간다") {
                    eventually(30.seconds) {
                        tables.publishedAt(OutboxSource.PRODUCT, healthy).shouldNotBeNull()
                        tables.row(OutboxSource.PRODUCT, sagaId)["status"] shouldBe "FAILED"
                    }
                    val failedRow = tables.row(OutboxSource.PRODUCT, sagaId)
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
                tables.claimedSecondsAgo(OutboxSource.PRODUCT, sagaId, 60)
                Thread.sleep(1500)

                Then("FAILED 행은 다시 집지 않아 실패가 더 세어지지 않고 알림도 더 나가지 않는다") {
                    tables.row(OutboxSource.PRODUCT, sagaId)["fail_count"] shouldBe 5
                    verify(exactly = 1) { alertSender.send(match<OutboxPublishFailedAlert> { it.messageId == messageId }) }
                }
            }

            When("관측이 돌면") {
                val delay = outboxService.delayOf(OutboxSource.PRODUCT)

                Then("FAILED 행 수가 보고된다") {
                    delay.failed shouldBeGreaterThanOrEqual 1L
                }
            }

            tables.delete(OutboxSource.PRODUCT, listOf(sagaId))
        }

        Given("다른 릴레이가 임대 중인, 이미 세 번 실패한 행") {
            val sagaId = UUID.randomUUID().toString()
            tables.insert(OutboxSource.ORDER, IntegrationTestConfig.REPLY_TOPIC, sagaId, now(), failCount = 3, held = true)
            val claimed = tables.message(OutboxSource.ORDER, sagaId)

            When("네 번째 실패를 기록하면") {
                val exhausted = outboxService.recordFailures(OutboxSource.ORDER, listOf(PublishFailure(claimed, "broker ack timed out")))

                Then("fail_count 가 4 가 되고 한도 전이라 PENDING 에 남아 FAILED 로 돌려주지 않는다") {
                    val row = tables.row(OutboxSource.ORDER, sagaId)
                    row["fail_count"] shouldBe 4
                    row["status"] shouldBe "PENDING"
                    row["last_error"] shouldBe "broker ack timed out"
                    row["failed_at"].shouldNotBeNull()
                    exhausted.shouldBeEmpty()
                }
            }

            When("다섯 번째 실패를 기록하면") {
                val exhausted = outboxService.recordFailures(OutboxSource.ORDER, listOf(PublishFailure(claimed, "E".repeat(300))))

                Then("fail_count 가 5 가 되며 FAILED 로 바뀌고 잘린 사유와 함께 그 행을 돌려준다") {
                    val row = tables.row(OutboxSource.ORDER, sagaId)
                    row["fail_count"] shouldBe 5
                    row["status"] shouldBe "FAILED"
                    exhausted shouldContainExactly listOf(OutboxFailure(claimed, 5, "E".repeat(255)))
                }
            }

            When("FAILED 가 된 뒤 늦은 실패를 또 기록하면") {
                val exhausted = outboxService.recordFailures(OutboxSource.ORDER, listOf(PublishFailure(claimed, "late")))

                Then("PENDING 이 아니라 세지 않고 FAILED 로 다시 돌려주지도 않는다") {
                    val row = tables.row(OutboxSource.ORDER, sagaId)
                    row["fail_count"] shouldBe 5
                    row["last_error"] shouldBe "E".repeat(255)
                    exhausted.shouldBeEmpty()
                }
            }

            tables.delete(OutboxSource.ORDER, listOf(sagaId))
        }

        Given("발행한 지 8일 된 PUBLISHED 행, 1일 된 PUBLISHED 행, 8일 전에 FAILED 가 된 행") {
            val old = UUID.randomUUID().toString()
            val recent = UUID.randomUUID().toString()
            val failed = UUID.randomUUID().toString()
            val eightDaysAgo = now().minusDays(8)
            tables.insert(OutboxSource.ORDER, IntegrationTestConfig.REPLY_TOPIC, old, occurredAt = eightDaysAgo, status = "PUBLISHED")
            tables.insert(OutboxSource.ORDER, IntegrationTestConfig.REPLY_TOPIC, recent, occurredAt = now(), status = "PUBLISHED")
            tables.insert(OutboxSource.ORDER, "no.such.topic", failed, occurredAt = eightDaysAgo, status = "FAILED", failCount = 5)
            tables.publishedDaysAgo(OutboxSource.ORDER, old, 8)
            tables.publishedDaysAgo(OutboxSource.ORDER, recent, 1)
            tables.failedDaysAgo(OutboxSource.ORDER, failed, 8)

            When("정리가 돌면") {
                publishedOutboxCleaner.cleanUp()

                Then("7일 지난 PUBLISHED 행만 지우고 FAILED 행은 사람이 볼 때까지 남긴다") {
                    tables.exists(OutboxSource.ORDER, old) shouldBe false
                    tables.publishedAt(OutboxSource.ORDER, recent) shouldNotBe null
                    tables.row(OutboxSource.ORDER, failed)["status"] shouldBe "FAILED"
                }
            }

            tables.delete(OutboxSource.ORDER, listOf(failed))
        }

        Given("발행한 지 8일 된 PUBLISHED 행과 1일 된 PUBLISHED 행") {
            val old = UUID.randomUUID().toString()
            val recent = UUID.randomUUID().toString()
            val incoming = UUID.randomUUID().toString()
            tables.insert(OutboxSource.ORDER, IntegrationTestConfig.REPLY_TOPIC, old, occurredAt = now().minusDays(8), status = "PUBLISHED")
            tables.insert(OutboxSource.ORDER, IntegrationTestConfig.REPLY_TOPIC, recent, occurredAt = now(), status = "PUBLISHED")
            tables.publishedDaysAgo(OutboxSource.ORDER, old, 8)
            tables.publishedDaysAgo(OutboxSource.ORDER, recent, 1)

            When("정리 청크가 커밋되기 전에 서비스가 새 PENDING 행을 넣으면") {
                val insert = runCatching {
                    TransactionTemplate(transactionManager).execute {
                        outboxRepository.deletePublishedBefore(OutboxSource.ORDER, PublishedOutboxRetentionPolicy.RETENTION_DAYS, PublishedOutboxRetentionPolicy.CLEANUP_CHUNK)
                        tables.insertWaitingAtMost(OutboxSource.ORDER, IntegrationTestConfig.REPLY_TOPIC, incoming, lockWaitSeconds = 1)
                    }
                }

                Then("정리가 미발행 구간을 잠그지 않아 서비스의 INSERT 가 기다리지 않고 들어간다") {
                    insert.exceptionOrNull().shouldBeNull()
                    tables.exists(OutboxSource.ORDER, incoming) shouldBe true
                    tables.exists(OutboxSource.ORDER, old) shouldBe false
                }
            }

            tables.delete(OutboxSource.ORDER, listOf(recent, incoming))
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
                val delay = outboxService.delayOf(OutboxSource.PAYMENT)
                delay.pending shouldBe 0L
                delay.failed shouldBe 0L
            }
        }
    }
}
