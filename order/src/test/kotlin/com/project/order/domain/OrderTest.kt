package com.project.order.domain

import com.project.common.exception.BusinessException
import com.project.order.exception.OrderErrorCode
import com.project.order.fixture.OrderFixture
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class OrderTest : BehaviorSpec({

    Given("주문 항목을 만들 때") {

        When("AC-10 주문 수량이 0이면") {
            val exception = shouldThrow<BusinessException> { OrderFixture.orderItem(quantity = 0L) }

            Then("INVALID_ORDER") {
                exception.errorCode shouldBe OrderErrorCode.INVALID_ORDER
                exception.message shouldContain "quantity=0"
            }
        }

        When("AC-10 주문 수량이 음수면") {
            val exception = shouldThrow<BusinessException> { OrderFixture.orderItem(quantity = -5L) }

            Then("INVALID_ORDER이고 메시지에 수량이 담긴다") {
                exception.errorCode shouldBe OrderErrorCode.INVALID_ORDER
                exception.message shouldContain "quantity=-5"
            }
        }

        When("AC-10 주문 수량이 상한 1000을 넘으면") {
            val exception = shouldThrow<BusinessException> { OrderFixture.orderItem(quantity = OrderItem.MAX_QUANTITY + 1) }

            Then("INVALID_ORDER이고 메시지에 수량이 담긴다") {
                exception.errorCode shouldBe OrderErrorCode.INVALID_ORDER
                exception.message shouldContain "quantity=1001"
            }
        }

        When("주문 수량이 상한 1000이면") {
            val item = OrderFixture.orderItem(quantity = OrderItem.MAX_QUANTITY)

            Then("허용된다") {
                item.quantity shouldBe 1000L
            }
        }
    }

    Given("사용자 7의 새 주문") {
        val order = OrderFixture.order(userId = 7L, id = null)

        When("아직 저장하지 않았으면") {

            Then("상태는 CREATED이고 id는 없다") {
                order.status shouldBe OrderStatus.CREATED
                order.isCompleted shouldBe false
                order.id.shouldBeNull()
                order.userId shouldBe 7L
            }
        }
    }
})
