package com.project.payment.client

import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.core.spec.style.BehaviorSpec

class LoggingAlertSenderTest : BehaviorSpec({

    Given("DLT 에 도착한 결제 커맨드의 알림") {
        val alert = DeadLetterAlert("cmd.payment", "1", "saga-1", "PAYMENT_PAY", "java.lang.IllegalStateException", "db down", DeadLetterKind.RETRY_EXHAUSTED, false)

        When("보내면") {

            Then("대역 구현이라 ERROR 로그만 남기고 예외를 내지 않는다") {
                shouldNotThrowAny { LoggingAlertSender().send(alert) }
            }
        }
    }
})
