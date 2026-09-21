package com.project.msa.domain

import com.project.msa.exception.BusinessException
import com.project.msa.exception.OrderErrorCode
import com.project.msa.fixture.OrderFixture
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class OrderTest : BehaviorSpec({

    Given("주문 항목을 만들 때") {

        When("AC-10 주문 수량이 0 이면") {
            val exception = shouldThrow<BusinessException> { OrderFixture.orderItem(quantity = 0L) }

            Then("INVALID_ORDER") {
                exception.errorCode shouldBe OrderErrorCode.INVALID_ORDER
                exception.message shouldContain "quantity=0"
            }
        }

        When("AC-10 주문 수량이 음수면") {
            val exception = shouldThrow<BusinessException> { OrderFixture.orderItem(quantity = -5L) }

            Then("INVALID_ORDER 이고 메시지에 수량이 담긴다") {
                exception.errorCode shouldBe OrderErrorCode.INVALID_ORDER
                exception.message shouldContain "quantity=-5"
            }
        }
    }

    Given("사용자 7 의 새 주문") {
        val order = OrderFixture.order(userId = 7L, id = null)

        When("아직 저장하지 않았으면") {

            Then("상태는 CREATED 이고 id 는 없다") {
                order.status shouldBe OrderStatus.CREATED
                order.isCompleted shouldBe false
                order.id.shouldBeNull()
                order.userId shouldBe 7L
            }
        }
    }
})
