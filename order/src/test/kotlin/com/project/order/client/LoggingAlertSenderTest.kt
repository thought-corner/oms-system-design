package com.project.order.client

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldHaveSize
import java.time.LocalDateTime

class LoggingAlertSenderTest : BehaviorSpec({

    Given("워치독과 DLT 핸들러가 만드는 네 종류의 알림") {
        val alerts: List<SagaAlert> = listOf(
            ForwardRecoveryFailedAlert("saga-1", 10L, "POINT", 3, "no reply for POINT"),
            CompensationFailedAlert("saga-1", 10L, 3, null, listOf("STOCK")),
            CompensationFailedAlert("saga-2", 11L, 3, null, emptyList()),
            OutboxStalledAlert("saga-1", 10L, "POINT_USE", "FAILED", LocalDateTime.of(2026, 9, 22, 3, 0), 1),
            ReplyDeadLetterAlert(DeadLetterKind.POISON, "saga.replies-dlt", "10", "saga-1", "POINT_USE", "tools.jackson.core.JacksonException", "broken"),
        )

        When("모두 보내면") {
            val sender = LoggingAlertSender()
            alerts.forEach { sender.send(it) }

            Then("모킹 구현이라 실제 발송 없이 로그만 남기고 예외를 내지 않는다") {
                alerts shouldHaveSize 5
            }
        }
    }
})
