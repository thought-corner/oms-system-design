package com.project.order.repository

import com.project.order.DbTag
import com.project.order.domain.OutboxStatus
import com.project.order.fixture.OrderFixture
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.extensions.spring.SpringTestLifecycleMode
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.jdbc.core.JdbcTemplate

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class OutboxMessageRepositoryTest : BehaviorSpec() {

    @Autowired
    lateinit var outboxMessageRepository: OutboxMessageRepository

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    init {
        extensions(SpringExtension(SpringTestLifecycleMode.Root))
        tags(DbTag)

        Given("사가 하나의 발행된 행 · 실패한 행 · 대기 행과 다른 사가의 대기 행") {
            val published = outboxMessageRepository.saveAndFlush(OrderFixture.outbox("STOCK_BUY", sagaId = "saga-ob-1"))
            outboxMessageRepository.saveAndFlush(OrderFixture.outbox("POINT_USE", sagaId = "saga-ob-1"))
            outboxMessageRepository.saveAndFlush(OrderFixture.outbox("PAYMENT_PAY", sagaId = "saga-ob-1"))
            outboxMessageRepository.saveAndFlush(OrderFixture.outbox("STOCK_BUY", sagaId = "saga-ob-2"))
            jdbcTemplate.update("UPDATE outbox SET status = 'PUBLISHED' WHERE id = ?", published.id)
            jdbcTemplate.update("UPDATE outbox SET status = 'FAILED', fail_count = 5 WHERE saga_id = 'saga-ob-1' AND message_type = 'POINT_USE'")

            When("미발행(PENDING·FAILED) 행을 찾으면") {
                val unpublished = outboxMessageRepository.findAllBySagaIdAndStatusInOrderByIdAsc(
                    "saga-ob-1",
                    listOf(OutboxStatus.PENDING, OutboxStatus.FAILED),
                )

                Then("그 사가의 두 행만 넣은 순서대로 나온다") {
                    unpublished.map { it.messageType } shouldContainExactly listOf("POINT_USE", "PAYMENT_PAY")
                }
            }

            When("서비스가 넣은 행의 릴레이 컬럼을 보면") {
                val row = jdbcTemplate.queryForMap(
                    "SELECT status, fail_count, message_id, claimed_at, published_at FROM outbox WHERE saga_id = 'saga-ob-2'",
                )

                Then("PENDING · 0 · message_id 로 들어가고 릴레이 컬럼은 비어 있다") {
                    row["status"] shouldBe "PENDING"
                    (row["fail_count"] as Number).toInt() shouldBe 0
                    row["message_id"] shouldBe "message-saga-ob-2-STOCK_BUY"
                    row["claimed_at"] shouldBe null
                    row["published_at"] shouldBe null
                }
            }
        }
    }
}
