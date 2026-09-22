package com.project.product.service

import com.project.product.domain.ProductTransactionHistory
import com.project.product.domain.ProductTransactionType
import com.project.common.exception.BusinessException
import com.project.product.exception.ProductErrorCode
import com.project.product.fixture.ProductFixture
import com.project.product.repository.ProductRepository
import com.project.product.repository.ProductTransactionHistoryRepository
import com.project.product.service.dto.BuyCancelCommand
import com.project.product.service.dto.BuyCommand
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Clock
import java.time.ZoneId

private val FIXED_CLOCK: Clock =
    Clock.fixed(ProductFixture.SEED_TIME.atZone(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault())

private fun buyCommand(productId: Long, quantity: Long, sagaId: String = "saga-1") =
    BuyCommand(sagaId = sagaId, orderId = 1L, items = listOf(BuyCommand.Item(productId, quantity)))

class ProductServiceTest : BehaviorSpec({

    Given("차감 이력이 없고 존재하지 않는 상품 9") {
        val productRepository = mockk<ProductRepository>()
        val historyRepository = mockk<ProductTransactionHistoryRepository>()
        val service = ProductService(productRepository, historyRepository, FIXED_CLOCK)
        every { historyRepository.findAllBySagaIdAndTransactionType(any(), ProductTransactionType.PURCHASE) } returns emptyList()
        every { productRepository.findWithLockById(9L) } returns null

        When("1개 사면") {
            val exception = shouldThrow<BusinessException> { service.buy(buyCommand(9L, 1L)) }

            Then("PRODUCT_NOT_FOUND") {
                exception.errorCode shouldBe ProductErrorCode.PRODUCT_NOT_FOUND
                exception.message shouldContain "productId=9"
            }
        }
    }

    Given("재고가 0인 상품 2") {
        val productRepository = mockk<ProductRepository>()
        val historyRepository = mockk<ProductTransactionHistoryRepository>()
        val service = ProductService(productRepository, historyRepository, FIXED_CLOCK)
        val product = ProductFixture.soldOut(id = 2L)
        every { historyRepository.findAllBySagaIdAndTransactionType(any(), ProductTransactionType.PURCHASE) } returns emptyList()
        every { productRepository.findWithLockById(2L) } returns product

        When("1개 사면") {
            val exception = shouldThrow<BusinessException> { service.buy(buyCommand(2L, 1L)) }

            Then("INSUFFICIENT_STOCK이고 재고는 0 그대로이며 이력을 남기지 않는다") {
                exception.errorCode shouldBe ProductErrorCode.INSUFFICIENT_STOCK
                product.quantity shouldBe 0L
                verify(exactly = 0) { historyRepository.save(any()) }
            }
        }
    }

    Given("재고 100 · 단가 200인 상품 2") {
        val productRepository = mockk<ProductRepository>()
        val historyRepository = mockk<ProductTransactionHistoryRepository>()
        val service = ProductService(productRepository, historyRepository, FIXED_CLOCK)
        val product = ProductFixture.product(id = 2L, price = 200L)
        every { historyRepository.findAllBySagaIdAndTransactionType(any(), ProductTransactionType.PURCHASE) } returns emptyList()
        every { productRepository.findWithLockById(2L) } returns product
        every { historyRepository.save(any()) } answers { firstArg() }

        When("3개 사면") {
            val totalPrice = service.buy(buyCommand(2L, 3L))

            Then("총액 600을 돌려주고 행 락으로 읽어 재고를 97로 차감하며 PURCHASE 이력을 남긴다") {
                totalPrice shouldBe 600L
                product.quantity shouldBe 97L
                verify(exactly = 1) { productRepository.findWithLockById(2L) }
                verify(exactly = 0) { productRepository.findById(any()) }
                verify(exactly = 1) {
                    historyRepository.save(match<ProductTransactionHistory> { it.transactionType == ProductTransactionType.PURCHASE })
                }
            }
        }
    }

    Given("같은 sagaId로 이미 차감한 이력이 있는 상품") {
        val productRepository = mockk<ProductRepository>()
        val historyRepository = mockk<ProductTransactionHistoryRepository>()
        val service = ProductService(productRepository, historyRepository, FIXED_CLOCK)
        every {
            historyRepository.findAllBySagaIdAndTransactionType("saga-1", ProductTransactionType.PURCHASE)
        } returns listOf(ProductFixture.history(price = 600L))

        When("같은 sagaId로 다시 차감을 요청하면") {
            val totalPrice = service.buy(buyCommand(2L, 3L))

            Then("첫 번째 총액을 그대로 돌려주고 재고를 건드리지 않는다") {
                totalPrice shouldBe 600L
                verify(exactly = 0) { productRepository.findWithLockById(any()) }
                verify(exactly = 0) { historyRepository.save(any()) }
            }
        }
    }

    Given("차감 이력이 없는 sagaId") {
        val productRepository = mockk<ProductRepository>()
        val historyRepository = mockk<ProductTransactionHistoryRepository>()
        val service = ProductService(productRepository, historyRepository, FIXED_CLOCK)
        every { historyRepository.findAllBySagaIdAndTransactionType("saga-none", any()) } returns emptyList()

        When("보상을 요청하면") {
            val restored = service.cancel(BuyCancelCommand(sagaId = "saga-none", orderId = 1L))

            Then("되돌릴 것이 없으므로 0을 돌려주고 실패하지 않는다") {
                restored shouldBe 0L
                verify(exactly = 0) { productRepository.findWithLockById(any()) }
            }
        }
    }

    Given("차감 이력이 있고 아직 되돌리지 않은 sagaId") {
        val productRepository = mockk<ProductRepository>()
        val historyRepository = mockk<ProductTransactionHistoryRepository>()
        val service = ProductService(productRepository, historyRepository, FIXED_CLOCK)
        val product = ProductFixture.product(id = 2L, quantity = 97L, price = 200L)
        every {
            historyRepository.findAllBySagaIdAndTransactionType("saga-1", ProductTransactionType.PURCHASE)
        } returns listOf(ProductFixture.history(productId = 2L, quantity = 3L, price = 600L))
        every {
            historyRepository.findAllBySagaIdAndTransactionType("saga-1", ProductTransactionType.CANCEL)
        } returns emptyList()
        every { productRepository.findWithLockById(2L) } returns product
        every { historyRepository.save(any()) } answers { firstArg() }

        When("보상을 요청하면") {
            val restored = service.cancel(BuyCancelCommand(sagaId = "saga-1", orderId = 1L))

            Then("재고가 100으로 돌아오고 CANCEL 이력을 남긴다") {
                restored shouldBe 600L
                product.quantity shouldBe 100L
                verify(exactly = 1) {
                    historyRepository.save(match<ProductTransactionHistory> { it.transactionType == ProductTransactionType.CANCEL })
                }
            }
        }
    }

    Given("이미 되돌린 sagaId") {
        val productRepository = mockk<ProductRepository>()
        val historyRepository = mockk<ProductTransactionHistoryRepository>()
        val service = ProductService(productRepository, historyRepository, FIXED_CLOCK)
        every {
            historyRepository.findAllBySagaIdAndTransactionType("saga-1", ProductTransactionType.PURCHASE)
        } returns listOf(ProductFixture.history(price = 600L))
        every {
            historyRepository.findAllBySagaIdAndTransactionType("saga-1", ProductTransactionType.CANCEL)
        } returns listOf(ProductFixture.history(price = 600L, transactionType = ProductTransactionType.CANCEL))

        When("같은 sagaId로 보상을 다시 요청하면") {
            val restored = service.cancel(BuyCancelCommand(sagaId = "saga-1", orderId = 1L))

            Then("첫 번째 복구 금액을 그대로 돌려주고 재고를 두 번 되돌리지 않는다") {
                restored shouldBe 600L
                verify(exactly = 0) { productRepository.findWithLockById(any()) }
                verify(exactly = 0) { historyRepository.save(any()) }
            }
        }
    }
})
