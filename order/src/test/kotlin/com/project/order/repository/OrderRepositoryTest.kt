package com.project.order.repository

import com.project.order.DbTag
import com.project.order.fixture.OrderFixture
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.extensions.spring.SpringTestLifecycleMode
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.dao.PessimisticLockingFailureException
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class OrderRepositoryTest : BehaviorSpec() {

    @Autowired
    lateinit var orderRepository: OrderRepository

    @Autowired
    lateinit var entityManager: EntityManager

    @Autowired
    lateinit var transactionManager: PlatformTransactionManager

    private fun newTransaction(): TransactionTemplate =
        TransactionTemplate(transactionManager).apply {
            propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
        }

    init {
        extensions(SpringExtension(SpringTestLifecycleMode.Root))
        tags(DbTag)

        Given("존재하지 않는 주문 999999") {

            When("잠금 읽기를 하면") {
                val found = orderRepository.findWithLockById(999_999L)

                Then("null을 돌려준다") {
                    found.shouldBeNull()
                }
            }
        }

        Given("다른 트랜잭션이 잠금 읽기로 잡고 있는 주문") {
            val committed = newTransaction()
            val orderId = committed.execute { requireNotNull(orderRepository.save(OrderFixture.order(id = null)).id) }!!
            val executor = Executors.newSingleThreadExecutor()
            val locked = CountDownLatch(1)
            val release = CountDownLatch(1)
            val holder = executor.submit {
                newTransaction().execute {
                    orderRepository.findWithLockById(orderId)
                    locked.countDown()
                    release.await(10, TimeUnit.SECONDS)
                }
            }

            When("테스트 트랜잭션이 같은 주문을 잠금 읽기 하면") {
                val exception = try {
                    locked.await(5, TimeUnit.SECONDS) shouldBe true
                    shouldThrow<PessimisticLockingFailureException> { orderRepository.findWithLockById(orderId) }
                } finally {
                    try {
                        release.countDown()
                        holder.get(5, TimeUnit.SECONDS)
                    } finally {
                        executor.shutdownNow()
                        committed.execute { orderRepository.deleteById(orderId) }
                    }
                }

                Then("AC-6 NOWAIT라 기다리지 않고 락 실패") {
                    exception.mostSpecificCause.message shouldContain "NOWAIT"
                }
            }
        }

        Given("테스트 트랜잭션 안에서 저장하고 영속성 컨텍스트를 비운 주문") {
            val orderId = requireNotNull(orderRepository.saveAndFlush(OrderFixture.order(id = null)).id)
            entityManager.clear()

            When("잠금 읽기를 하면") {
                val found = orderRepository.findWithLockById(orderId)

                Then("PESSIMISTIC_WRITE 락이 걸려 있다") {
                    entityManager.getLockMode(found) shouldBe LockModeType.PESSIMISTIC_WRITE
                }
            }
        }
    }
}
