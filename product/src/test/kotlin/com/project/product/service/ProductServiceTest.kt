package com.project.product.service

import com.project.product.domain.ProductTransactionHistory
import com.project.product.domain.ProductTransactionType
import com.project.product.domain.SagaGuardKind
import com.project.common.exception.BusinessException
import com.project.product.exception.ProductErrorCode
import com.project.product.fixture.ProductFixture
import com.project.product.repository.ProductRepository
import com.project.product.repository.ProductTransactionHistoryRepository
import com.project.product.repository.SagaGuardRepository
import com.project.product.service.dto.BuyCancelCommand
import com.project.product.service.dto.BuyCommand
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import java.time.Clock
import java.time.ZoneId

private val FIXED_CLOCK: Clock =
    Clock.fixed(ProductFixture.SEED_TIME.atZone(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault())

private fun buyCommand(productId: Long, quantity: Long, sagaId: String = "saga-1") =
    BuyCommand(sagaId = sagaId, orderId = 1L, items = listOf(BuyCommand.Item(productId, quantity)))

private fun guardRepository(kind: SagaGuardKind = SagaGuardKind.FORWARD): SagaGuardRepository =
    mockk<SagaGuardRepository>().also {
        every { it.insertIfAbsent(any(), any(), any()) } just Runs
        every { it.findWithLockBySagaId(any()) } answers { ProductFixture.guard(sagaId = firstArg(), kind = kind) }
    }

class ProductServiceTest : BehaviorSpec({

    Given("차감 이력이 없고 존재하지 않는 상품 9") {
        val productRepository = mockk<ProductRepository>()
        val historyRepository = mockk<ProductTransactionHistoryRepository>()
        val service = ProductService(productRepository, historyRepository, guardRepository(), FIXED_CLOCK)
        every { historyRepository.findAllBySagaIdAndTransactionType(any(), any()) } returns emptyList()
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
        val service = ProductService(productRepository, historyRepository, guardRepository(), FIXED_CLOCK)
        val product = ProductFixture.soldOut(id = 2L)
        every { historyRepository.findAllBySagaIdAndTransactionType(any(), any()) } returns emptyList()
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
        val guardRepository = guardRepository()
        val service = ProductService(productRepository, historyRepository, guardRepository, FIXED_CLOCK)
        val product = ProductFixture.product(id = 2L, price = 200L)
        every { historyRepository.findAllBySagaIdAndTransactionType(any(), any()) } returns emptyList()
        every { productRepository.findWithLockById(2L) } returns product
        every { historyRepository.save(any()) } answers { firstArg() }

        When("3개 사면") {
            val totalPrice = service.buy(buyCommand(2L, 3L))

            Then("사가 가드를 FORWARD로 잡은 뒤 행 락으로 재고를 97로 차감하고 총액 600과 PURCHASE 이력을 남긴다") {
                totalPrice shouldBe 600L
                product.quantity shouldBe 97L
                verifyOrder {
                    guardRepository.insertIfAbsent("saga-1", SagaGuardKind.FORWARD.name, any())
                    guardRepository.findWithLockBySagaId("saga-1")
                    historyRepository.findAllBySagaIdAndTransactionType("saga-1", any())
                    productRepository.findWithLockById(2L)
                }
                verify(exactly = 0) { productRepository.findById(any()) }
                verify(exactly = 1) {
                    historyRepository.save(match<ProductTransactionHistory> { it.transactionType == ProductTransactionType.PURCHASE })
                }
            }
        }
    }

    Given("재고 100 · 단가 200인 상품 2가 두 줄로 나뉘어 들어온 차감 요청") {
        val productRepository = mockk<ProductRepository>()
        val historyRepository = mockk<ProductTransactionHistoryRepository>()
        val service = ProductService(productRepository, historyRepository, guardRepository(), FIXED_CLOCK)
        val product = ProductFixture.product(id = 2L, price = 200L)
        every { historyRepository.findAllBySagaIdAndTransactionType(any(), any()) } returns emptyList()
        every { productRepository.findWithLockById(2L) } returns product
        every { historyRepository.save(any()) } answers { firstArg() }
        val command = BuyCommand(
            sagaId = "saga-1",
            orderId = 1L,
            items = listOf(BuyCommand.Item(2L, 1L), BuyCommand.Item(2L, 2L)),
        )

        When("사면") {
            val totalPrice = service.buy(command)

            Then("수량을 합쳐 한 번 잠그고 한 번 차감하며 PURCHASE 이력을 한 줄만 남긴다") {
                totalPrice shouldBe 600L
                product.quantity shouldBe 97L
                verify(exactly = 1) { productRepository.findWithLockById(2L) }
                verify(exactly = 1) {
                    historyRepository.save(match<ProductTransactionHistory> { it.productId == 2L && it.quantity == 3L })
                }
            }
        }
    }

    Given("같은 상품 2가 합계가 Long 범위를 넘는 세 줄로 들어온 차감 요청") {
        val productRepository = mockk<ProductRepository>()
        val historyRepository = mockk<ProductTransactionHistoryRepository>()
        val service = ProductService(productRepository, historyRepository, guardRepository(), FIXED_CLOCK)
        every { historyRepository.findAllBySagaIdAndTransactionType(any(), any()) } returns emptyList()
        val command = BuyCommand(
            sagaId = "saga-1",
            orderId = 1L,
            items = listOf(
                BuyCommand.Item(2L, 6148914691236517206L),
                BuyCommand.Item(2L, 6148914691236517206L),
                BuyCommand.Item(2L, 6148914691236517209L),
            ),
        )

        When("사면") {
            shouldThrow<ArithmeticException> { service.buy(command) }

            Then("합계가 작은 수로 감겨 통과하지 않고 산술 예외로 실패하며 재고를 잠그지도 않는다 — order 가 상한으로 막는 입력이라 불변식 위반으로 드러낸다") {
                verify(exactly = 0) { productRepository.findWithLockById(any()) }
                verify(exactly = 0) { historyRepository.save(any()) }
            }
        }
    }

    Given("같은 sagaId로 이미 차감한 이력이 있는 상품") {
        val productRepository = mockk<ProductRepository>()
        val historyRepository = mockk<ProductTransactionHistoryRepository>()
        val service = ProductService(productRepository, historyRepository, guardRepository(), FIXED_CLOCK)
        every {
            historyRepository.findAllBySagaIdAndTransactionType("saga-1", ProductTransactionType.CANCEL)
        } returns emptyList()
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

    Given("보상이 먼저 도착해 CANCEL 가드가 남은 sagaId") {
        val productRepository = mockk<ProductRepository>()
        val historyRepository = mockk<ProductTransactionHistoryRepository>()
        val service = ProductService(productRepository, historyRepository, guardRepository(SagaGuardKind.CANCEL), FIXED_CLOCK)

        When("늦게 도착한 차감 요청이 오면") {
            val exception = shouldThrow<BusinessException> { service.buy(buyCommand(2L, 3L)) }

            Then("SAGA_ALREADY_COMPENSATED로 거부하고 재고를 건드리지 않는다") {
                exception.errorCode shouldBe ProductErrorCode.SAGA_ALREADY_COMPENSATED
                verify(exactly = 0) { productRepository.findWithLockById(any()) }
                verify(exactly = 0) { historyRepository.save(any()) }
            }
        }
    }

    Given("차감한 뒤 이미 되돌린 sagaId") {
        val productRepository = mockk<ProductRepository>()
        val historyRepository = mockk<ProductTransactionHistoryRepository>()
        val service = ProductService(productRepository, historyRepository, guardRepository(), FIXED_CLOCK)
        every {
            historyRepository.findAllBySagaIdAndTransactionType("saga-1", ProductTransactionType.CANCEL)
        } returns listOf(ProductFixture.history(transactionType = ProductTransactionType.CANCEL))

        When("같은 sagaId로 차감 요청이 다시 오면") {
            val exception = shouldThrow<BusinessException> { service.buy(buyCommand(2L, 3L)) }

            Then("SAGA_ALREADY_COMPENSATED로 거부한다") {
                exception.errorCode shouldBe ProductErrorCode.SAGA_ALREADY_COMPENSATED
                verify(exactly = 0) { productRepository.findWithLockById(any()) }
            }
        }
    }

    Given("차감 이력이 없는 sagaId") {
        val productRepository = mockk<ProductRepository>()
        val historyRepository = mockk<ProductTransactionHistoryRepository>()
        val guardRepository = guardRepository(SagaGuardKind.CANCEL)
        val service = ProductService(productRepository, historyRepository, guardRepository, FIXED_CLOCK)
        every { historyRepository.findAllBySagaIdAndTransactionType("saga-none", any()) } returns emptyList()

        When("보상을 요청하면") {
            val restored = service.cancel(BuyCancelCommand(sagaId = "saga-none", orderId = 1L))

            Then("CANCEL 가드를 남기고 0을 돌려주며 실패하지 않는다") {
                restored shouldBe 0L
                verify(exactly = 1) { guardRepository.insertIfAbsent("saga-none", SagaGuardKind.CANCEL.name, any()) }
                verify(exactly = 0) { productRepository.findWithLockById(any()) }
            }
        }
    }

    Given("상품 3과 상품 1을 차감한 이력이 있고 아직 되돌리지 않은 sagaId") {
        val productRepository = mockk<ProductRepository>()
        val historyRepository = mockk<ProductTransactionHistoryRepository>()
        val service = ProductService(productRepository, historyRepository, guardRepository(), FIXED_CLOCK)
        val product1 = ProductFixture.product(id = 1L, quantity = 98L, price = 100L)
        val product3 = ProductFixture.product(id = 3L, quantity = 97L, price = 200L)
        every {
            historyRepository.findAllBySagaIdAndTransactionType("saga-1", ProductTransactionType.PURCHASE)
        } returns listOf(
            ProductFixture.history(orderId = 5L, productId = 3L, quantity = 3L, price = 600L),
            ProductFixture.history(orderId = 5L, productId = 1L, quantity = 2L, price = 200L),
        )
        every {
            historyRepository.findAllBySagaIdAndTransactionType("saga-1", ProductTransactionType.CANCEL)
        } returns emptyList()
        every { productRepository.findWithLockById(1L) } returns product1
        every { productRepository.findWithLockById(3L) } returns product3
        every { historyRepository.save(any()) } answers { firstArg() }

        When("보상을 요청하면") {
            val restored = service.cancel(BuyCancelCommand(sagaId = "saga-1", orderId = 1L))

            Then("productId 오름차순으로 잠가 재고를 되돌리고 차감 이력의 주문으로 CANCEL 이력을 남긴다") {
                restored shouldBe 800L
                product1.quantity shouldBe 100L
                product3.quantity shouldBe 100L
                verifyOrder {
                    productRepository.findWithLockById(1L)
                    productRepository.findWithLockById(3L)
                }
                verify(exactly = 2) {
                    historyRepository.save(
                        match<ProductTransactionHistory> {
                            it.transactionType == ProductTransactionType.CANCEL && it.orderId == 5L
                        },
                    )
                }
            }
        }
    }

    Given("이미 되돌린 sagaId") {
        val productRepository = mockk<ProductRepository>()
        val historyRepository = mockk<ProductTransactionHistoryRepository>()
        val service = ProductService(productRepository, historyRepository, guardRepository(), FIXED_CLOCK)
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
