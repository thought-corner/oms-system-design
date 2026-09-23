package com.project.product.service

import com.project.product.DbTag
import com.project.product.domain.ProductTransactionType
import com.project.product.fixture.ProductFixture
import com.project.product.repository.ProductRepository
import com.project.product.repository.ProductTransactionHistoryRepository
import com.project.product.repository.SagaGuardRepository
import com.project.product.service.dto.BuyCancelCommand
import com.project.product.service.dto.BuyCommand
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
class ProductServiceConcurrencyTest : BehaviorSpec() {

    @Autowired
    lateinit var productRepository: ProductRepository

    @Autowired
    lateinit var historyRepository: ProductTransactionHistoryRepository

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

        Given("재고 차감이 가드를 쥔 채 아직 커밋하지 않은 사가") {
            val sagaId = "saga-concurrency-product"
            val productId = 9001L
            val clock = Clock.fixed(ProductFixture.SEED_TIME.atZone(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault())
            val service = ProductService(productRepository, historyRepository, guardRepository, clock)
            newTransaction().execute { productRepository.save(ProductFixture.product(id = productId, quantity = 100L, price = 200L)) }
            val executor = Executors.newFixedThreadPool(2)
            val locked = CountDownLatch(1)
            val release = CountDownLatch(1)

            When("같은 사가의 보상이 도착하면") {
                val result = try {
                    val buyer = executor.submit {
                        newTransaction().execute {
                            service.buy(BuyCommand(sagaId, 1L, listOf(BuyCommand.Item(productId, 3L))))
                            locked.countDown()
                            release.await(10, TimeUnit.SECONDS)
                        }
                    }
                    locked.await(5, TimeUnit.SECONDS) shouldBe true
                    val canceller = executor.submit<Long> { newTransaction().execute { service.cancel(BuyCancelCommand(sagaId, 1L)) } }
                    val waited = try {
                        Thread.sleep(500)
                        !canceller.isDone
                    } finally {
                        release.countDown()
                    }
                    buyer.get(10, TimeUnit.SECONDS)
                    val restored = canceller.get(10, TimeUnit.SECONDS)
                    val quantity = newTransaction().execute { productRepository.findById(productId).get().quantity }
                    Triple(waited, restored, quantity)
                } finally {
                    executor.shutdownNow()
                    newTransaction().execute {
                        historyRepository.deleteAll(
                            historyRepository.findAllBySagaIdAndTransactionType(sagaId, ProductTransactionType.PURCHASE) +
                                historyRepository.findAllBySagaIdAndTransactionType(sagaId, ProductTransactionType.CANCEL),
                        )
                        guardRepository.deleteById(sagaId)
                        productRepository.deleteById(productId)
                    }
                }

                Then("보상은 가드에서 기다렸다가 커밋된 차감을 보고 재고를 되돌린다") {
                    result.first shouldBe true
                    result.second shouldBe 600L
                    result.third shouldBe 100L
                }
            }
        }
    }
}
