package com.project.operation.integration

import com.project.operation.DbTag
import com.project.operation.client.AlertSender
import com.project.operation.client.OutboxPublishFailedAlert
import com.project.operation.domain.OutboxSource
import com.project.operation.service.policy.OutboxRelayPolicy
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.verify
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.kafka.KafkaContainer
import org.testcontainers.mysql.MySQLContainer
import java.time.LocalDateTime
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

@SpringBootTest
@Import(IntegrationTestConfig::class, BrokerOutageTestConfig::class)
class OutboxRelayBrokerOutageIntegrationTest : BehaviorSpec() {

    @Autowired
    lateinit var mysql: MySQLContainer

    @Autowired
    lateinit var kafka: KafkaContainer

    @Autowired
    lateinit var alertSender: AlertSender

    private var paused = false

    private val root: JdbcTemplate by lazy {
        JdbcTemplate(DriverManagerDataSource(mysql.jdbcUrl, "root", IntegrationTestConfig.PASSWORD))
    }

    private fun pauseBroker() {
        kafka.dockerClient.pauseContainerCmd(kafka.containerId).exec()
        paused = true
    }

    private fun resumeBroker() {
        if (paused) {
            kafka.dockerClient.unpauseContainerCmd(kafka.containerId).exec()
            paused = false
        }
    }

    private fun insertHeld(topic: String, sagaId: String) {
        root.update(
            "INSERT INTO ${OutboxSource.ORDER.table} (message_id, topic, message_key, saga_id, message_type, payload, status, occurred_at, claimed_at) VALUES (?, ?, '10', ?, 'STOCK_BUY', '{}', 'PENDING', NOW(6), NOW(6) + INTERVAL 1 DAY)",
            UUID.randomUUID().toString(),
            topic,
            sagaId,
        )
    }

    private fun expireLease(sagaIds: List<String>) {
        root.update(
            "UPDATE ${OutboxSource.ORDER.table} SET claimed_at = NULL WHERE saga_id IN (${sagaIds.joinToString { "?" }})",
            *sagaIds.toTypedArray(),
        )
    }

    private fun row(sagaId: String): Map<String, Any?> =
        root.queryForMap("SELECT * FROM ${OutboxSource.ORDER.table} WHERE saga_id = ?", sagaId)

    private fun claimedAt(sagaId: String): LocalDateTime? =
        root.queryForObject("SELECT claimed_at FROM ${OutboxSource.ORDER.table} WHERE saga_id = ?", LocalDateTime::class.java, sagaId)

    init {
        extensions(SpringExtension())
        tags(DbTag)

        afterSpec { resumeBroker() }

        Given("브로커가 멈춘 동안 order 스키마에 쌓인 두 토픽의 미발행 행") {
            val sagaIds = listOf(UUID.randomUUID().toString(), UUID.randomUUID().toString())
            pauseBroker()
            insertHeld(IntegrationTestConfig.COMMAND_TOPIC, sagaIds[0])
            insertHeld(IntegrationTestConfig.REPLY_TOPIC, sagaIds[1])

            When("임대가 실패 한도보다 많이 끝나 릴레이가 매번 다시 집어 보내도") {
                repeat(OutboxRelayPolicy.MAX_PUBLISH_ATTEMPTS + 2) {
                    expireLease(sagaIds)
                    eventually(10.seconds) {
                        sagaIds.forEach { claimedAt(it).shouldNotBeNull() }
                    }
                    Thread.sleep(ATTEMPT_WINDOW_MS)
                }

                Then("브로커에 닿지 않은 배치라 실패를 세지 않아 fail_count 0 인 PENDING 으로 남고 FAILED 알림도 없다") {
                    sagaIds.forEach { sagaId ->
                        val pending = row(sagaId)
                        pending["status"] shouldBe "PENDING"
                        pending["fail_count"] shouldBe 0
                        pending["failed_at"].shouldBeNull()
                        pending["published_at"].shouldBeNull()
                    }
                    verify(exactly = 0) { alertSender.send(any<OutboxPublishFailedAlert>()) }
                }
            }

            When("브로커가 돌아온 뒤 임대가 끝나면") {
                resumeBroker()
                expireLease(sagaIds)

                Then("같은 행이 FAILED 에 이르지 않고 발행되어 PUBLISHED 가 된다") {
                    eventually(75.seconds) {
                        sagaIds.forEach { row(it)["status"] shouldBe "PUBLISHED" }
                    }
                    sagaIds.forEach { (row(it)["fail_count"] as Int) shouldBeLessThan OutboxRelayPolicy.MAX_PUBLISH_ATTEMPTS }
                    verify(exactly = 0) { alertSender.send(any<OutboxPublishFailedAlert>()) }
                }
            }
        }
    }

    companion object {
        private const val ATTEMPT_WINDOW_MS = 3000L
    }
}
