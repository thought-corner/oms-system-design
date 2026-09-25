package com.project.product.init

import com.project.product.domain.Product
import com.project.product.repository.ProductRepository
import io.kotest.core.spec.style.BehaviorSpec
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify

class ProductDataCreatorTest : BehaviorSpec({

    Given("상품 행이 없는 스키마") {
        val productRepository = mockk<ProductRepository>(relaxed = true)
        every { productRepository.count() } returns 0L
        every { productRepository.save(any<Product>()) } answers { firstArg() }

        When("시드를 만들면") {
            ProductDataCreator(productRepository).createSeedData()

            Then("상품 1·2 를 재고 100 으로 넣는다") {
                verify(exactly = 1) { productRepository.save(match<Product> { it.id == 1L && it.quantity == 100L && it.price == 100L }) }
                verify(exactly = 1) { productRepository.save(match<Product> { it.id == 2L && it.quantity == 100L && it.price == 200L }) }
            }
        }
    }

    Given("ddl-auto=update 로 다시 띄워 차감된 재고가 남아 있는 스키마") {
        val productRepository = mockk<ProductRepository>(relaxed = true)
        every { productRepository.count() } returns 2L

        When("시드를 만들면") {
            ProductDataCreator(productRepository).createSeedData()

            Then("남은 재고를 시드 값으로 덮지 않는다") {
                verify(exactly = 0) { productRepository.save(any<Product>()) }
            }
        }
    }
})
