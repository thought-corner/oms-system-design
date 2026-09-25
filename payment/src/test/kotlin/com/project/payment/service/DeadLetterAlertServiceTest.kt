package com.project.payment.service

import com.project.common.exception.CommonErrorCode
import com.project.payment.client.AlertSender
import com.project.payment.client.DeadLetterAlert
import com.project.payment.client.DeadLetterKind
import com.project.payment.service.dto.DeadLetterCommand
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.springframework.dao.DataAccessResourceFailureException

private const val SAGA_ID = "7f1c2a4e-3b9d-4c1a-9e2f-0a1b2c3d4e5f"

private const val PARSE_FAILURE = "com.google.protobuf.InvalidProtocolBufferException"

private fun deadLetter(
    messageType: String? = "PAYMENT_PAY",
    orderId: String? = "10",
    sagaId: String? = SAGA_ID,
    exceptionClass: String? = PARSE_FAILURE,
) = DeadLetterCommand("payment.command.dlt", orderId, sagaId, messageType, exceptionClass, "broken")

private fun alertOf(alertSender: AlertSender): DeadLetterAlert {
    val alert = slot<DeadLetterAlert>()
    verify(exactly = 1) { alertSender.send(capture(alert)) }
    return alert.captured
}

class DeadLetterAlertServiceTest : BehaviorSpec({

    Given("재시도를 모두 소진한 일시 장애의 PAYMENT_PAY") {
        val paymentService = mockk<PaymentService>()
        val alertSender = mockk<AlertSender>(relaxed = true)
        val service = DeadLetterAlertService(paymentService, alertSender)

        When("DLT 에서 처리하면") {
            service.handle(deadLetter(exceptionClass = "org.springframework.dao.CannotAcquireLockException"))

            Then("업무 결과로 바꾸지 않고 RETRY_EXHAUSTED 로 알리기만 한다") {
                verify { paymentService wasNot Called }
                alertOf(alertSender) shouldBe DeadLetterAlert(
                    "payment.command.dlt", "10", SAGA_ID, "PAYMENT_PAY", "org.springframework.dao.CannotAcquireLockException", "broken",
                    DeadLetterKind.RETRY_EXHAUSTED, false,
                )
            }
        }
    }

    Given("원인 예외 이름이 없는 DLT 레코드") {
        val paymentService = mockk<PaymentService>()
        val alertSender = mockk<AlertSender>(relaxed = true)
        val service = DeadLetterAlertService(paymentService, alertSender)

        When("DLT 에서 처리하면") {
            service.handle(deadLetter(exceptionClass = null))

            Then("poison 이라고 단정하지 않고 RETRY_EXHAUSTED 로 알린다") {
                verify { paymentService wasNot Called }
                alertOf(alertSender).kind shouldBe DeadLetterKind.RETRY_EXHAUSTED
            }
        }
    }

    Given("재시도 불가 예외로 DLT 에 온 PAYMENT_CANCEL") {
        val paymentService = mockk<PaymentService>()
        val alertSender = mockk<AlertSender>(relaxed = true)
        val service = DeadLetterAlertService(paymentService, alertSender)

        When("DLT 에서 처리하면") {
            service.handle(deadLetter(messageType = "PAYMENT_CANCEL"))

            Then("보상은 실패로 응답하지 않으므로 POISON 알림만 보낸다") {
                verify { paymentService wasNot Called }
                val alert = alertOf(alertSender)
                alert.kind shouldBe DeadLetterKind.POISON
                alert.failedReplyWritten shouldBe false
            }
        }
    }

    Given("재시도 불가 예외로 DLT 에 온 PAYMENT_PAY") {
        val paymentService = mockk<PaymentService>(relaxed = true)
        val alertSender = mockk<AlertSender>(relaxed = true)
        val service = DeadLetterAlertService(paymentService, alertSender)

        When("DLT 에서 처리하면") {
            service.handle(deadLetter())

            Then("키의 orderId 와 헤더의 sagaId 로 INTERNAL_ERROR 실패 응답을 쓰고 그 사실을 실어 알린다") {
                verify(exactly = 1) { paymentService.recordPayFailure(SAGA_ID, 10L, CommonErrorCode.INTERNAL_ERROR) }
                val alert = alertOf(alertSender)
                alert.kind shouldBe DeadLetterKind.POISON
                alert.failedReplyWritten shouldBe true
            }
        }
    }

    Given("재시도 불가 예외로 DLT 에 왔지만 응답을 만들 값이 온전하지 않은 PAYMENT_PAY") {
        val broken = listOf(
            deadLetter(orderId = null),
            deadLetter(orderId = "order-10"),
            deadLetter(sagaId = null),
            deadLetter(sagaId = "saga-1"),
            deadLetter(messageType = null),
            deadLetter(messageType = "POINT_USE"),
        )

        broken.forEach { command ->
            When("${command.orderId}·${command.sagaId}·${command.messageType} 를 처리하면") {
                val paymentService = mockk<PaymentService>()
                val alertSender = mockk<AlertSender>(relaxed = true)
                DeadLetterAlertService(paymentService, alertSender).handle(command)

                Then("응답을 쓰지 않고 POISON 알림만 보낸다") {
                    verify { paymentService wasNot Called }
                    val alert = alertOf(alertSender)
                    alert.kind shouldBe DeadLetterKind.POISON
                    alert.failedReplyWritten shouldBe false
                }
            }
        }
    }

    Given("실패 응답을 쓰는 중 DB 가 끊긴 poison PAYMENT_PAY") {
        val paymentService = mockk<PaymentService>()
        every { paymentService.recordPayFailure(SAGA_ID, 10L, CommonErrorCode.INTERNAL_ERROR) } throws
            DataAccessResourceFailureException("db down")
        val alertSender = mockk<AlertSender>(relaxed = true)
        val service = DeadLetterAlertService(paymentService, alertSender)

        When("DLT 에서 처리하면") {

            Then("예외를 내지 않고 응답을 못 썼다고 알린다") {
                shouldNotThrowAny { service.handle(deadLetter()) }
                val alert = alertOf(alertSender)
                alert.kind shouldBe DeadLetterKind.POISON
                alert.failedReplyWritten shouldBe false
            }
        }
    }
})
