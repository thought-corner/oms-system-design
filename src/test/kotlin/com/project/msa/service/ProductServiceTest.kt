package com.project.msa.service

import com.project.msa.exception.BusinessException
import com.project.msa.exception.ProductErrorCode
import com.project.msa.fixture.ProductFixture
import com.project.msa.repository.ProductRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify

class ProductServiceTest : BehaviorSpec({

    Given("존재하지 않는 상품 9") {
        val productRepository = mockk<ProductRepository>()
        val service = ProductService(productRepository)
        every { productRepository.findWithLockById(9L) } returns null

        When("1개 사면") {
            val exception = shouldThrow<BusinessException> { service.buy(9L, 1L) }

            Then("PRODUCT_NOT_FOUND") {
                exception.errorCode shouldBe ProductErrorCode.PRODUCT_NOT_FOUND
                exception.message shouldContain "productId=9"
            }
        }
    }

    Given("재고가 0 인 상품 2") {
        val productRepository = mockk<ProductRepository>()
        val service = ProductService(productRepository)
        val product = ProductFixture.soldOut(id = 2L)
        every { productRepository.findWithLockById(2L) } returns product

        When("1개 사면") {
            val exception = shouldThrow<BusinessException> { service.buy(2L, 1L) }

            Then("INSUFFICIENT_STOCK 이고 재고는 0 그대로") {
                exception.errorCode shouldBe ProductErrorCode.INSUFFICIENT_STOCK
                product.quantity shouldBe 0L
            }
        }
    }

    Given("재고 100 · 단가 200 인 상품 2") {
        val productRepository = mockk<ProductRepository>()
        val service = ProductService(productRepository)
        val product = ProductFixture.product(price = 200L, id = 2L)
        every { productRepository.findWithLockById(2L) } returns product

        When("3개 사면") {
            val totalPrice = service.buy(2L, 3L)

            Then("총액 600 을 돌려주고 행 락으로 읽어 재고를 97 로 차감한다") {
                totalPrice shouldBe 600L
                product.quantity shouldBe 97L
                verify(exactly = 1) { productRepository.findWithLockById(2L) }
                verify(exactly = 0) { productRepository.findById(any()) }
            }
        }
    }
})
