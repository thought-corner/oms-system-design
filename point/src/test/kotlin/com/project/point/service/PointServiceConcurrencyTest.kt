package com.project.point.service

import com.project.point.DbTag
import com.project.point.domain.PointTransactionType
import com.project.point.fixture.PointFixture
import com.project.point.repository.PointRepository
import com.project.point.repository.PointTransactionHistoryRepository
import com.project.point.repository.SagaGuardRepository
import com.project.point.service.dto.UseCancelCommand
import com.project.point.service.dto.UseCommand
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.extensions.spring.SpringTestLifecycleMode
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class PointServiceConcurrencyTest : BehaviorSpec() {

    @Autowired
    lateinit var pointRepository: PointRepository

    @Autowired
    lateinit var historyRepository: PointTransactionHistoryRepository

    @Autowired
    lateinit var guardRepository: SagaGuardRepository

    @Autowired
    lateinit var transactionManager: PlatformTransactionManager

    private fun newTransaction(): TransactionTemplate =
        TransactionTemplate(transactionManager).apply {
            propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
        }

    init {
        extensions(SpringExtension(SpringTestLifecycleMode.Root))
        tags(DbTag)

        Given("포인트 사용이 가드를 쥔 채 아직 커밋하지 않은 사가") {
            val sagaId = "saga-concurrency-point"
            val userId = 9001L
            val clock = Clock.fixed(PointFixture.SEED_TIME.atZone(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault())
            val service = PointService(pointRepository, historyRepository, guardRepository, clock)
            newTransaction().execute { pointRepository.save(PointFixture.point(userId = userId, amount = 10000L, id = null)) }
            val executor = Executors.newFixedThreadPool(2)
            val locked = CountDownLatch(1)
            val release = CountDownLatch(1)

            When("같은 사가의 보상이 도착하면") {
                val result = try {
                    val user = executor.submit {
                        newTransaction().execute {
                            service.use(UseCommand(sagaId, 1L, userId, 400L))
                            locked.countDown()
                            release.await(10, TimeUnit.SECONDS)
                        }
                    }
                    locked.await(5, TimeUnit.SECONDS) shouldBe true
                    val canceller = executor.submit<Long> { newTransaction().execute { service.cancel(UseCancelCommand(sagaId, 1L)) } }
                    val waited = try {
                        Thread.sleep(500)
                        !canceller.isDone
                    } finally {
                        release.countDown()
                    }
                    user.get(10, TimeUnit.SECONDS)
                    val refunded = canceller.get(10, TimeUnit.SECONDS)
                    val amount = newTransaction().execute { pointRepository.findWithLockByUserId(userId)?.amount }
                    Triple(waited, refunded, amount)
                } finally {
                    executor.shutdownNow()
                    newTransaction().execute {
                        PointTransactionType.entries.forEach { type ->
                            historyRepository.findBySagaIdAndTransactionType(sagaId, type)?.let { historyRepository.delete(it) }
                        }
                        guardRepository.deleteById(sagaId)
                        pointRepository.findWithLockByUserId(userId)?.let { pointRepository.delete(it) }
                    }
                }

                Then("보상은 가드에서 기다렸다가 커밋된 사용을 보고 잔액을 되돌린다") {
                    result.first shouldBe true
                    result.second shouldBe 400L
                    result.third shouldBe 10000L
                }
            }
        }
    }
}
