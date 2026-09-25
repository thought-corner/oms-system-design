package com.project.product.client

import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.core.spec.style.BehaviorSpec

class LoggingAlertSenderTest : BehaviorSpec({

    Given("DLT 에 도착한 커맨드의 알림") {
        val alert = DeadLetterAlert("cmd.product-dlt", "10", "saga-1", "STOCK_BUY", "tools.jackson.core.JacksonException", "broken", DeadLetterKind.POISON, true)

        When("알림을 보내면") {

            Then("대역 구현이라 로그만 남기고 예외를 내지 않는다") {
                shouldNotThrowAny { LoggingAlertSender().send(alert) }
            }
        }
    }
})
