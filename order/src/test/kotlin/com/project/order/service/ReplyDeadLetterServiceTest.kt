package com.project.order.service

import com.project.order.client.AlertSender
import com.project.order.client.DeadLetterKind
import com.project.order.client.ReplyDeadLetterAlert
import com.project.order.config.ReplyRetryPolicy
import com.project.order.service.dto.DeadLetterCommand
import com.project.order.service.policy.ReplyDeadLetterPolicy
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify

private fun deadLetter(exceptionClass: String?) =
    DeadLetterCommand("saga.replies-dlt", "10", "saga-1", "POINT_USE", exceptionClass, "broken")

private fun kindOf(exceptionClass: String?): DeadLetterKind {
    val alertSender = mockk<AlertSender>(relaxed = true)
    val alert = slot<ReplyDeadLetterAlert>()
    ReplyDeadLetterService(alertSender).alert(deadLetter(exceptionClass))
    verify(exactly = 1) { alertSender.send(capture(alert)) }
    return alert.captured.kind
}

class ReplyDeadLetterServiceTest : BehaviorSpec({

    Given("JSON 을 읽지 못해 곧장 DLT 로 간 응답") {
        val alertSender = mockk<AlertSender>(relaxed = true)
        val command = deadLetter("tools.jackson.core.JacksonException")

        When("DLT 핸들러가 넘기면") {
            ReplyDeadLetterService(alertSender).alert(command)

            Then("B-13 POISON 으로 토픽·키·헤더·예외를 담아 운영자에게 알린다") {
                verify(exactly = 1) {
                    alertSender.send(
                        ReplyDeadLetterAlert(
                            DeadLetterKind.POISON,
                            "saga.replies-dlt",
                            "10",
                            "saga-1",
                            "POINT_USE",
                            "tools.jackson.core.JacksonException",
                            "broken",
                        ),
                    )
                }
            }
        }
    }

    Given("재시도 불가 예외의 하위 클래스와 나머지 재시도 불가 예외가 원인인 응답") {

        When("분류하면") {
            val kinds = listOf(
                "tools.jackson.core.exc.StreamReadException",
                "java.lang.ArithmeticException",
                "java.lang.NumberFormatException",
            ).map { kindOf(it) }

            Then("모두 POISON 이다") {
                kinds.forEach { it shouldBe DeadLetterKind.POISON }
            }
        }
    }

    Given("재시도를 소진한 예외, 알 수 없는 클래스, 원인 없는 응답") {

        When("분류하면") {
            val kinds = listOf(
                "org.springframework.dao.CannotAcquireLockException",
                "com.example.NoSuchException",
                null,
            ).map { kindOf(it) }

            Then("모두 RETRY_EXHAUSTED 이다") {
                kinds.forEach { it shouldBe DeadLetterKind.RETRY_EXHAUSTED }
            }
        }
    }

    Given("소비자 재시도 정책의 재시도 불가 목록") {

        Then("DLT 의 POISON 판정 목록과 같다") {
            ReplyDeadLetterPolicy.POISON_CAUSES shouldContainExactlyInAnyOrder ReplyRetryPolicy.NON_RETRYABLE
        }
    }
})
