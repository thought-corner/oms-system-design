package com.project.operation.client

import com.project.operation.fixture.OutboxFixture
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.core.spec.style.BehaviorSpec

class LoggingAlertSenderTest : BehaviorSpec({

    Given("발행이 밀린 스키마") {
        val alert = OutboxDelayAlert("payment", 40, OutboxFixture.FIXED_TIME, 400, 2)

        When("알림을 보내면") {
            Then("대역 구현이라 로그만 남기고 예외를 내지 않는다") {
                shouldNotThrowAny { LoggingAlertSender().send(alert) }
            }
        }
    }

    Given("발행에 다섯 번 실패해 FAILED 가 된 행") {
        val alert = OutboxPublishFailedAlert("point", 7, "message-7", "no.such.topic", "saga-7", "POINT_USE", 5, "TimeoutException: not present in metadata")

        When("알림을 보내면") {
            Then("대역 구현이라 로그만 남기고 예외를 내지 않는다") {
                shouldNotThrowAny { LoggingAlertSender().send(alert) }
            }
        }
    }
})
