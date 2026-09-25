package com.project.operation.integration

import com.project.operation.DbTag
import com.project.operation.domain.OutboxSource
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.mysql.MySQLContainer
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

@SpringBootTest
@Import(IntegrationTestConfig::class, BrokerOutageTestConfig::class)
class OutboxRelayMissingTopicIntegrationTest : BehaviorSpec() {

    @Autowired
    lateinit var mysql: MySQLContainer

    private val root: JdbcTemplate by lazy {
        JdbcTemplate(DriverManagerDataSource(mysql.jdbcUrl, "root", IntegrationTestConfig.PASSWORD))
    }

    private fun insertHeld(topic: String, sagaId: String) {
        root.update(
            "INSERT INTO ${OutboxSource.ORDER.table} (message_id, topic, message_key, saga_id, message_type, payload, status, occurred_at, claimed_at) VALUES (?, ?, '10', ?, 'STOCK_BUY', ?, 'PENDING', NOW(6), NOW(6) + INTERVAL 1 DAY)",
            UUID.randomUUID().toString(),
            topic,
            sagaId,
            byteArrayOf(0x0A, 0x02),
        )
    }

    private fun releaseTogether(sagaIds: List<String>) {
        root.update(
            "UPDATE ${OutboxSource.ORDER.table} SET claimed_at = NULL WHERE saga_id IN (${sagaIds.joinToString { "?" }})",
            *sagaIds.toTypedArray(),
        )
    }

    private fun delete(sagaIds: List<String>) {
        root.update(
            "DELETE FROM ${OutboxSource.ORDER.table} WHERE saga_id IN (${sagaIds.joinToString { "?" }})",
            *sagaIds.toTypedArray(),
        )
    }

    private fun row(sagaId: String): Map<String, Any?> =
        root.queryForMap("SELECT * FROM ${OutboxSource.ORDER.table} WHERE saga_id = ?", sagaId)

    init {
        extensions(SpringExtension())
        tags(DbTag)

        Given("order 스키마에서 없는 토픽의 행 셋 뒤에 정상 토픽의 행") {
            val missingSagaIds = List(3) { UUID.randomUUID().toString() }
            val healthySagaId = UUID.randomUUID().toString()

            When("네 행을 한꺼번에 풀어 릴레이가 한 배치로 집어 보내면") {
                missingSagaIds.forEach { insertHeld(MISSING_TOPIC, it) }
                insertHeld(IntegrationTestConfig.COMMAND_TOPIC, healthySagaId)
                releaseTogether(missingSagaIds + healthySagaId)

                Then("없는 토픽은 첫 행만 시도해 한 번 세고 같은 토픽의 뒤 행은 건너뛰며, 정상 행은 발행된다") {
                    try {
                        eventually(20.seconds) {
                            row(healthySagaId)["status"] shouldBe "PUBLISHED"
                            row(missingSagaIds[0])["fail_count"] shouldBe 1
                        }
                        missingSagaIds.drop(1).forEach { sagaId ->
                            val skipped = row(sagaId)
                            skipped["status"] shouldBe "PENDING"
                            skipped["fail_count"] shouldBe 0
                        }
                    } finally {
                        delete(missingSagaIds)
                    }
                }
            }
        }
    }

    companion object {
        private const val MISSING_TOPIC = "cmd.missing"
    }
}
