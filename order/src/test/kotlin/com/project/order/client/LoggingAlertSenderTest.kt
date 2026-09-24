package com.project.order.client

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

private fun alert(stock: Boolean = false, point: Boolean = false, payment: Boolean = false) =
    CompensationFailedAlert(
        sagaId = "saga-1",
        orderId = 10L,
        attempts = 3,
        lastError = "product down",
        stockDone = stock,
        pointDone = point,
        paymentDone = payment,
    )

class LoggingAlertSenderTest : BehaviorSpec({

    Given("재고와 포인트가 묶인 채 보상이 실패한 사가") {
        val a = alert(stock = true, point = true)

        Then("무엇이 묶였는지 알림에 담긴다") {
            a.stuck shouldContainExactly listOf("stock", "point")
        }

        When("알림을 보내면") {
            LoggingAlertSender().send(a)

            Then("모킹 구현이라 실제 발송 없이 로그만 남기고 예외를 내지 않는다") {
                a.orderId shouldBe 10L
            }
        }
    }

    Given("어느 단계도 성공하지 못한 사가") {
        val a = alert()

        Then("묶인 것이 없다") {
            a.stuck shouldContainExactly emptyList()
        }

        When("알림을 보내면") {
            LoggingAlertSender().send(a)

            Then("묶인 것이 없어도 알림은 나간다 — 보상 실패 자체가 개입 대상이다") {
                a.attempts shouldBe 3
            }
        }
    }

    Given("세 단계가 모두 성공한 뒤 보상이 실패한 사가") {
        val a = alert(stock = true, point = true, payment = true)

        Then("세 가지가 모두 묶인 것으로 보고된다") {
            a.stuck shouldContainExactly listOf("stock", "point", "payment")
        }
    }

    Given("결제까지 끝났지만 완료로 닫지 못한 사가") {
        val a = ForwardRecoveryFailedAlert(sagaId = "saga-1", orderId = 10L, attempts = 3, lastError = "lock wait timeout")

        When("알림을 보내면") {
            LoggingAlertSender().send(a)

            Then("보상 실패와 다른 종류로 로그만 남기고 예외를 내지 않는다") {
                a.attempts shouldBe 3
            }
        }
    }
})
