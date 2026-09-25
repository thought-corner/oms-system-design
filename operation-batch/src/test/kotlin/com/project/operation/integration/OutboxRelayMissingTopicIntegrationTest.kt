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
import org.testcontainers.mysql.MySQLContainer
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

@SpringBootTest
@Import(IntegrationTestConfig::class, BrokerOutageTestConfig::class)
class OutboxRelayMissingTopicIntegrationTest : BehaviorSpec() {

    @Autowired
    lateinit var mysql: MySQLContainer

    private val tables: OutboxTables by lazy { OutboxTables(mysql) }

    init {
        extensions(SpringExtension())
        tags(DbTag)

        Given("order 스키마에서 없는 토픽의 행 셋 뒤에 정상 토픽의 행") {
            val missingSagaIds = List(3) { UUID.randomUUID().toString() }
            val healthySagaId = UUID.randomUUID().toString()

            When("네 행을 한꺼번에 풀어 릴레이가 한 배치로 집어 보내면") {
                missingSagaIds.forEach { tables.insert(OutboxSource.ORDER, MISSING_TOPIC, it, held = true) }
                tables.insert(OutboxSource.ORDER, IntegrationTestConfig.COMMAND_TOPIC, healthySagaId, held = true)
                tables.release(OutboxSource.ORDER, missingSagaIds + healthySagaId)

                Then("없는 토픽은 첫 행만 시도해 한 번 세고 같은 토픽의 뒤 행은 건너뛰며, 정상 행은 발행된다") {
                    try {
                        eventually(20.seconds) {
                            tables.row(OutboxSource.ORDER, healthySagaId)["status"] shouldBe "PUBLISHED"
                            tables.row(OutboxSource.ORDER, missingSagaIds[0])["fail_count"] shouldBe 1
                        }
                        missingSagaIds.drop(1).forEach { sagaId ->
                            val skipped = tables.row(OutboxSource.ORDER, sagaId)
                            skipped["status"] shouldBe "PENDING"
                            skipped["fail_count"] shouldBe 0
                        }
                    } finally {
                        tables.delete(OutboxSource.ORDER, missingSagaIds)
                    }
                }
            }
        }
    }

    companion object {
        private const val MISSING_TOPIC = "missing.command"
    }
}
