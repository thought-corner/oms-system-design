package com.project.msa.domain

import com.project.msa.exception.BusinessException
import com.project.msa.exception.PointErrorCode
import com.project.msa.fixture.PointFixture
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain

class PointTest : BehaviorSpec({

    Given("잔액 100 인 포인트") {
        val point = PointFixture.point(amount = 100L)

        When("잔액보다 많은 101 을 쓰면") {
            val exception = shouldThrow<BusinessException> { point.use(101L) }

            Then("INSUFFICIENT_POINT 이고 잔액은 그대로") {
                exception.errorCode shouldBe PointErrorCode.INSUFFICIENT_POINT
                exception.message shouldNotContain "balance"
                point.amount shouldBe 100L
            }
        }

        When("음수 금액을 쓰면") {
            val exception = shouldThrow<IllegalArgumentException> { point.use(-500L) }

            Then("거부되고 잔액은 그대로") {
                exception.message shouldBe "amount=-500"
                point.amount shouldBe 100L
            }
        }
    }

    Given("잔액 400 인 포인트") {
        val point = PointFixture.point(amount = 400L)

        When("잔액과 같은 400 을 쓰면") {
            point.use(400L)

            Then("잔액은 0") {
                point.amount shouldBe 0L
            }
        }
    }
})
