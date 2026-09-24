package com.project.payment.statemachine

import com.project.common.exception.BusinessException
import com.project.payment.domain.PaymentEvent
import com.project.payment.domain.PaymentStatus
import com.project.payment.exception.PaymentErrorCode
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class PaymentStateMachineTest : BehaviorSpec({

    Given("결제 상태 기계") {
        val stateMachine = PaymentStateMachine()

        When("PAID인 결제 1에 CANCEL을 보내면") {
            val next = stateMachine.transition(1L, PaymentStatus.PAID, PaymentEvent.CANCEL)

            Then("다음 상태는 CANCELED") {
                next shouldBe PaymentStatus.CANCELED
            }
        }

        When("이미 CANCELED인 결제 1에 CANCEL을 다시 보내면") {
            val exception = shouldThrow<BusinessException> {
                stateMachine.transition(1L, PaymentStatus.CANCELED, PaymentEvent.CANCEL)
            }

            Then("INVALID_PAYMENT_STATE_TRANSITION이고 메시지에 상태와 이벤트가 담긴다") {
                exception.errorCode shouldBe PaymentErrorCode.INVALID_PAYMENT_STATE_TRANSITION
                exception.message shouldContain "paymentId=1"
                exception.message shouldContain "status=CANCELED, event=CANCEL"
            }
        }
    }
})
