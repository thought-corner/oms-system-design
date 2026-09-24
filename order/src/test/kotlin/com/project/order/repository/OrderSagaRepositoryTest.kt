package com.project.order.repository

import com.project.order.DbTag
import com.project.order.domain.OrderSaga
import com.project.order.domain.SagaStatus
import com.project.order.fixture.OrderFixture
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.string.shouldContain
import org.springframework.dao.DataIntegrityViolationException
import io.kotest.extensions.spring.SpringExtension
import io.kotest.extensions.spring.SpringTestLifecycleMode
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private val RECOVERABLE = listOf(SagaStatus.RUNNING, SagaStatus.COMPENSATING, SagaStatus.COMPENSATION_FAILED)

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class OrderSagaRepositoryTest : BehaviorSpec() {

    @Autowired
    lateinit var sagaRepository: OrderSagaRepository

    @Autowired
    lateinit var transactionManager: PlatformTransactionManager

    private fun newTransaction(): TransactionTemplate =
        TransactionTemplate(transactionManager).apply {
            propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
        }

    init {
        extensions(SpringExtension(SpringTestLifecycleMode.Root))
        tags(DbTag)

        Given("5분 전에 멈춘 RUNNING 사가와 방금 갱신된 RUNNING 사가, 5분 전에 끝난 SUCCEEDED 사가") {
            val threshold = OrderFixture.FIXED_TIME.minusSeconds(60)
            sagaRepository.save(OrderFixture.saga(sagaId = "saga-stale", createdAt = OrderFixture.FIXED_TIME.minusMinutes(5)))
            sagaRepository.save(OrderFixture.saga(sagaId = "saga-fresh", createdAt = OrderFixture.FIXED_TIME))
            sagaRepository.saveAndFlush(
                OrderFixture.saga(sagaId = "saga-done", createdAt = OrderFixture.FIXED_TIME.minusMinutes(5))
                    .also { it.transitionTo(SagaStatus.SUCCEEDED, OrderFixture.FIXED_TIME.minusMinutes(5)) },
            )

            When("각각 집으려 하면") {
                val stale = sagaRepository.findWithLockBySagaIdAndStatusInAndUpdatedAtLessThan("saga-stale", RECOVERABLE, threshold)
                val fresh = sagaRepository.findWithLockBySagaIdAndStatusInAndUpdatedAtLessThan("saga-fresh", RECOVERABLE, threshold)
                val done = sagaRepository.findWithLockBySagaIdAndStatusInAndUpdatedAtLessThan("saga-done", RECOVERABLE, threshold)

                Then("임계를 넘긴 복구 대상만 집히고 방금 갱신된 사가와 끝난 사가는 집히지 않는다") {
                    stale?.sagaId shouldBe "saga-stale"
                    fresh.shouldBeNull()
                    done.shouldBeNull()
                }
            }
        }

        Given("다른 트랜잭션이 집어 잠그고 있는 멈춘 사가") {
            val threshold = OrderFixture.FIXED_TIME.minusSeconds(60)
            val sagaId = "saga-claimed"
            newTransaction().execute {
                sagaRepository.save(OrderFixture.saga(sagaId = sagaId, createdAt = OrderFixture.FIXED_TIME.minusMinutes(5)))
            }
            val executor = Executors.newSingleThreadExecutor()
            val locked = CountDownLatch(1)
            val release = CountDownLatch(1)
            val holder = executor.submit {
                newTransaction().execute {
                    sagaRepository.findWithLockBySagaIdAndStatusInAndUpdatedAtLessThan(sagaId, RECOVERABLE, threshold)
                    locked.countDown()
                    release.await(10, TimeUnit.SECONDS)
                }
            }

            When("테스트 트랜잭션이 같은 사가를 집으려 하면") {
                val claimed = try {
                    locked.await(5, TimeUnit.SECONDS) shouldBe true
                    sagaRepository.findWithLockBySagaIdAndStatusInAndUpdatedAtLessThan(sagaId, RECOVERABLE, threshold)
                } finally {
                    try {
                        release.countDown()
                        holder.get(5, TimeUnit.SECONDS)
                    } finally {
                        executor.shutdownNow()
                        newTransaction().execute { sagaRepository.findBySagaId(sagaId)?.let { sagaRepository.delete(it) } }
                    }
                }

                Then("SKIP LOCKED라 기다리지 않고 건너뛴다") {
                    claimed.shouldBeNull()
                }
            }
        }

        Given("주문 7001 에 key-a 로 연 사가") {
            sagaRepository.saveAndFlush(OrderFixture.saga(sagaId = "saga-7001-a", orderId = 7001L, idempotencyKey = "key-a"))

            When("같은 주문에 같은 키로 사가를 하나 더 만들면") {
                val exception = shouldThrow<DataIntegrityViolationException> {
                    sagaRepository.saveAndFlush(OrderFixture.saga(sagaId = "saga-7001-dup", orderId = 7001L, idempotencyKey = "key-a"))
                }

                Then("B-2 UNIQUE(order_id, idempotency_key) 가 막는다") {
                    exception.mostSpecificCause.message shouldContain OrderSaga.UK_ORDER_ID_IDEMPOTENCY_KEY
                }
            }
        }

        Given("주문 7002 의 실패한 사가와 재결제로 연 사가") {
            sagaRepository.save(OrderFixture.saga(sagaId = "saga-7002-a", orderId = 7002L, idempotencyKey = "key-a"))
            sagaRepository.saveAndFlush(OrderFixture.saga(sagaId = "saga-7002-b", orderId = 7002L, idempotencyKey = "key-b"))

            When("키로 찾고 최신 사가를 고르면") {
                val sameKey = sagaRepository.existsByOrderIdAndIdempotencyKey(7002L, "key-a")
                val otherKey = sagaRepository.existsByOrderIdAndIdempotencyKey(7002L, "key-c")
                val latest = sagaRepository.findFirstByOrderIdOrderByIdDesc(7002L)

                Then("다른 주문의 같은 키와 섞이지 않고 최신은 재결제 사가다") {
                    sameKey shouldBe true
                    otherKey shouldBe false
                    latest?.sagaId shouldBe "saga-7002-b"
                }
            }
        }
    }
}
