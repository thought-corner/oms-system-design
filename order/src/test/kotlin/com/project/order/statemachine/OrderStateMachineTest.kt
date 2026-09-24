package com.project.order.statemachine

import com.project.order.domain.OrderEvent
import com.project.order.domain.OrderStatus
import com.project.common.exception.BusinessException
import com.project.order.exception.OrderErrorCode
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class OrderStateMachineTest : BehaviorSpec({

    Given("주문 상태 기계") {
        val stateMachine = OrderStateMachine()

        When("COMPLETED인 주문 1에 PLACE를 보내면") {
            val exception = shouldThrow<BusinessException> {
                stateMachine.transition(1L, OrderStatus.COMPLETED, OrderEvent.PLACE)
            }

            Then("INVALID_ORDER_STATE_TRANSITION이고 메시지에 상태와 이벤트가 담긴다") {
                exception.errorCode shouldBe OrderErrorCode.INVALID_ORDER_STATE_TRANSITION
                exception.message shouldContain "orderId=1"
                exception.message shouldContain "status=COMPLETED, event=PLACE"
            }
        }

        When("CREATED인 주문 1에 COMPLETE를 보내면") {
            val exception = shouldThrow<BusinessException> {
                stateMachine.transition(1L, OrderStatus.CREATED, OrderEvent.COMPLETE)
            }

            Then("사가를 시작하지 않은 주문은 완료될 수 없다") {
                exception.errorCode shouldBe OrderErrorCode.INVALID_ORDER_STATE_TRANSITION
            }
        }

        When("CREATED인 주문 1에 FAIL을 보내면") {
            val exception = shouldThrow<BusinessException> {
                stateMachine.transition(1L, OrderStatus.CREATED, OrderEvent.FAIL)
            }

            Then("거부된다") {
                exception.errorCode shouldBe OrderErrorCode.INVALID_ORDER_STATE_TRANSITION
            }
        }

        When("CREATED인 주문 1에 PLACE를 보내면") {
            val next = stateMachine.transition(1L, OrderStatus.CREATED, OrderEvent.PLACE)

            Then("다음 상태는 PLACING") {
                next shouldBe OrderStatus.PLACING
            }
        }

        When("PLACING인 주문 1에 COMPLETE를 보내면") {
            val next = stateMachine.transition(1L, OrderStatus.PLACING, OrderEvent.COMPLETE)

            Then("다음 상태는 COMPLETED") {
                next shouldBe OrderStatus.COMPLETED
            }
        }

        When("PLACING인 주문 1에 FAIL을 보내면") {
            val next = stateMachine.transition(1L, OrderStatus.PLACING, OrderEvent.FAIL)

            Then("다음 상태는 FAILED") {
                next shouldBe OrderStatus.FAILED
            }
        }

        When("FAILED인 주문 1에 PLACE를 보내면") {
            val next = stateMachine.transition(1L, OrderStatus.FAILED, OrderEvent.PLACE)

            Then("재결제가 허용되어 PLACING으로 간다") {
                next shouldBe OrderStatus.PLACING
            }
        }
    }
})
