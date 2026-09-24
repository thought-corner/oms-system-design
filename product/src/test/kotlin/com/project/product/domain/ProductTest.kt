package com.project.product.domain

import com.project.common.exception.BusinessException
import com.project.product.exception.ProductErrorCode
import com.project.product.fixture.ProductFixture
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class ProductTest : BehaviorSpec({

    Given("재고 1인 상품") {
        val product = ProductFixture.product(quantity = 1L)

        When("2 개를 사면") {
            val exception = shouldThrow<BusinessException> { product.buy(2L) }

            Then("INSUFFICIENT_STOCK이고 재고는 그대로") {
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

        When("0 개를 복구하면") {
            val exception = shouldThrow<IllegalArgumentException> { product.restore(0L) }

            Then("거부되고 재고는 그대로") {
                exception.message shouldBe "quantity=0"
                product.quantity shouldBe 1L
            }
        }
    }

    Given("품절 상품") {
        val product = ProductFixture.soldOut()

        When("1 개를 사면") {
            val exception = shouldThrow<BusinessException> { product.buy(1L) }

            Then("INSUFFICIENT_STOCK이고 재고는 0") {
                exception.errorCode shouldBe ProductErrorCode.INSUFFICIENT_STOCK
                exception.message shouldContain "stock=0, requested=1"
                product.quantity shouldBe 0L
            }
        }
    }

    Given("재고 100 · 단가 200인 상품") {
        val product = ProductFixture.product(quantity = 100L, price = 200L)

        When("3 개를 사면") {
            product.buy(3L)

            Then("재고는 97이고 가격은 600") {
                product.quantity shouldBe 97L
                product.calculatePrice(3L) shouldBe 600L
            }
        }

        When("3 개를 사고 다시 3 개를 복구하면") {
            val restored = ProductFixture.product(quantity = 100L, price = 200L)
            restored.buy(3L)
            restored.restore(3L)

            Then("재고가 100으로 돌아온다") {
                restored.quantity shouldBe 100L
            }
        }
    }
})
