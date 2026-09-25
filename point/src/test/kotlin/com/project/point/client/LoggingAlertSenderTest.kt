package com.project.point.client

import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.core.spec.style.BehaviorSpec

class LoggingAlertSenderTest : BehaviorSpec({

    Given("헤더가 비어 있는 DLT 커맨드") {
        val alert = DeadLetterAlert("cmd.point", null, null, null, null, null, DeadLetterKind.RETRY_EXHAUSTED, false)

        When("알림을 보내면") {

            Then("대역 구현이라 로그만 남기고 예외를 내지 않는다") {
                shouldNotThrowAny { LoggingAlertSender().send(alert) }
            }
        }
    }
})
