package com.project.msa.domain

import com.project.msa.exception.BusinessException
import com.project.msa.exception.ProductErrorCode
import com.project.msa.fixture.ProductFixture
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class ProductTest : BehaviorSpec({

    Given("재고 1 인 상품") {
        val product = ProductFixture.product(quantity = 1L)

        When("2 개를 사면") {
            val exception = shouldThrow<BusinessException> { product.buy(2L) }

            Then("INSUFFICIENT_STOCK 이고 재고는 그대로") {
                exception.errorCode shouldBe ProductErrorCode.INSUFFICIENT_STOCK
                exception.message shouldContain "stock=1, requested=2"
                product.quantity shouldBe 1L
            }
        }

        When("0 개를 사면") {
            val exception = shouldThrow<IllegalArgumentException> { product.buy(0L) }

            Then("거부되고 재고는 그대로") {
                exception.message shouldBe "quantity=0"
                product.quantity shouldBe 1L
            }
        }

        When("음수 개를 사면") {
            val exception = shouldThrow<IllegalArgumentException> { product.buy(-3L) }

            Then("거부되고 재고는 그대로") {
                exception.message shouldBe "quantity=-3"
                product.quantity shouldBe 1L
            }
        }
    }

    Given("품절 상품") {
        val product = ProductFixture.soldOut()

        When("1 개를 사면") {
            val exception = shouldThrow<BusinessException> { product.buy(1L) }

            Then("INSUFFICIENT_STOCK 이고 재고는 0") {
                exception.errorCode shouldBe ProductErrorCode.INSUFFICIENT_STOCK
                exception.message shouldContain "stock=0, requested=1"
                product.quantity shouldBe 0L
            }
        }
    }

    Given("재고 100 · 단가 200 인 상품") {
        val product = ProductFixture.product(quantity = 100L, price = 200L)

        When("3 개를 사면") {
            product.buy(3L)

            Then("재고는 97 이고 가격은 600") {
                product.quantity shouldBe 97L
                product.calculatePrice(3L) shouldBe 600L
            }
        }
    }
})
