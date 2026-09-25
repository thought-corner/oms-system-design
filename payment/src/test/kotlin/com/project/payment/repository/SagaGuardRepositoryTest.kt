package com.project.payment.repository

import com.project.payment.DbTag
import com.project.payment.domain.SagaGuardKind
import com.project.payment.fixture.PaymentFixture
import com.project.payment.fixture.newTransaction
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.extensions.spring.SpringTestLifecycleMode
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.transaction.PlatformTransactionManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class SagaGuardRepositoryTest : BehaviorSpec() {

    @Autowired
    lateinit var guardRepository: SagaGuardRepository

    @Autowired
    lateinit var transactionManager: PlatformTransactionManager

    private fun lockGuard(sagaId: String, kind: SagaGuardKind) {
        guardRepository.insertIfAbsent(sagaId, kind.name, PaymentFixture.FIXED_NOW)
        guardRepository.findWithLockBySagaId(sagaId)
    }

    init {
        extensions(SpringExtension(SpringTestLifecycleMode.Root))
        tags(DbTag)

        Given("가드가 없는 sagaId") {

            When("FORWARD로 넣은 뒤 같은 sagaId를 CANCEL로 다시 넣으면") {
                guardRepository.insertIfAbsent("saga-guard-1", SagaGuardKind.FORWARD.name, PaymentFixture.FIXED_NOW)
                guardRepository.insertIfAbsent("saga-guard-1", SagaGuardKind.CANCEL.name, PaymentFixture.FIXED_NOW)
                val found = guardRepository.findWithLockBySagaId("saga-guard-1")

                Then("두 번째는 예외 없이 무시되고 먼저 들어간 종류가 남는다") {
                    found?.kind shouldBe SagaGuardKind.FORWARD
                }
            }
        }

        Given("다른 트랜잭션이 이미 있는 가드를 잡고 있는 sagaId") {
            val sagaId = "saga-guard-2"
            transactionManager.newTransaction().execute { guardRepository.insertIfAbsent(sagaId, SagaGuardKind.FORWARD.name, PaymentFixture.FIXED_NOW) }
            val executor = Executors.newFixedThreadPool(2)
            val locked = CountDownLatch(1)
            val release = CountDownLatch(1)

            When("같은 sagaId의 보상이 가드를 잡으려 하면") {
                val holder = executor.submit {
                    transactionManager.newTransaction().execute {
                        guardRepository.insertIfAbsent(sagaId, SagaGuardKind.FORWARD.name, PaymentFixture.FIXED_NOW)
                        locked.countDown()
                        release.await(10, TimeUnit.SECONDS)
                        guardRepository.findWithLockBySagaId(sagaId)
                    }
                }
                locked.await(5, TimeUnit.SECONDS) shouldBe true
                val waiter = executor.submit { transactionManager.newTransaction().execute { lockGuard(sagaId, SagaGuardKind.CANCEL) } }
                val waitedWhileHeld = try {
                    Thread.sleep(500)
                    !waiter.isDone
                } finally {
                    release.countDown()
                }
                val outcome = runCatching {
                    holder.get(10, TimeUnit.SECONDS)
                    waiter.get(10, TimeUnit.SECONDS)
                }
                executor.shutdownNow()
                val kind = transactionManager.newTransaction().execute {
                    guardRepository.findById(sagaId).map { it.kind }.orElse(null).also { guardRepository.deleteById(sagaId) }
                }

                Then("잡은 쪽이 끝날 때까지 기다렸다가 데드락 없이 이어 가고 먼저 들어간 종류가 남는다") {
                    waitedWhileHeld shouldBe true
                    outcome.exceptionOrNull() shouldBe null
                    kind shouldBe SagaGuardKind.FORWARD
                }
            }
        }
    }
}
