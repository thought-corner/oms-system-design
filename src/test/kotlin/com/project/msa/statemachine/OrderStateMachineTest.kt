package com.project.msa.statemachine

import com.project.msa.domain.OrderEvent
import com.project.msa.domain.OrderStatus
import com.project.msa.exception.BusinessException
import com.project.msa.exception.OrderErrorCode
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class OrderStateMachineTest : BehaviorSpec({

    Given("주문 상태 기계") {
        val stateMachine = OrderStateMachine()

        When("COMPLETED 인 주문 1 에 PLACE 를 보내면") {
            val exception = shouldThrow<BusinessException> {
                stateMachine.transition(1L, OrderStatus.COMPLETED, OrderEvent.PLACE)
            }

            Then("INVALID_ORDER_STATE_TRANSITION 이고 메시지에 상태와 이벤트가 담긴다") {
                exception.errorCode shouldBe OrderErrorCode.INVALID_ORDER_STATE_TRANSITION
                exception.message shouldContain "orderId=1"
                exception.message shouldContain "status=COMPLETED, event=PLACE"
            }
        }

        When("CREATED 인 주문 1 에 PLACE 를 보내면") {
            val next = stateMachine.transition(1L, OrderStatus.CREATED, OrderEvent.PLACE)

            Then("다음 상태는 COMPLETED") {
                next shouldBe OrderStatus.COMPLETED
            }
        }
    }
})
