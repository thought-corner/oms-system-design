package com.project.payment.service

import com.project.payment.domain.Payment
import com.project.payment.domain.PaymentStatus
import com.project.payment.fixture.PaymentFixture
import com.project.payment.fixture.withId
import com.project.common.exception.BusinessException
import com.project.payment.exception.PaymentErrorCode
import com.project.payment.repository.PaymentRepository
import com.project.payment.service.dto.PayCancelCommand
import com.project.payment.service.dto.PayCommand
import com.project.payment.statemachine.PaymentStateMachine
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify

private fun payCommand(sagaId: String = PaymentFixture.DEFAULT_SAGA_ID) =
    PayCommand(
        sagaId = sagaId,
        orderId = PaymentFixture.DEFAULT_ORDER_ID,
        userId = PaymentFixture.DEFAULT_USER_ID,
        amount = PaymentFixture.DEFAULT_AMOUNT,
    )

class PaymentServiceTest : BehaviorSpec({

    Given("같은 sagaId로 이미 결제한 이력이 있는 주문") {
        val paymentRepository = mockk<PaymentRepository>()
        val service = PaymentService(paymentRepository, PaymentStateMachine(), PaymentFixture.FIXED_CLOCK)
        every { paymentRepository.findBySagaId(PaymentFixture.DEFAULT_SAGA_ID) } returns PaymentFixture.payment().withId(7L)

        When("같은 sagaId로 결제를 다시 요청하면") {
            val result = service.pay(payCommand())

            Then("외부 승인을 기다리지 않고 첫 번째 결제를 그대로 돌려준다") {
                result.paymentId shouldBe 7L
                result.paidAt shouldBe PaymentFixture.FIXED_PAID_AT
                verify(exactly = 0) { paymentRepository.save(any()) }
            }
        }
    }

    Given("결제 이력이 없는 주문") {
        val paymentRepository = mockk<PaymentRepository>()
        val service = PaymentService(paymentRepository, PaymentStateMachine(), PaymentFixture.FIXED_CLOCK)
        every { paymentRepository.findBySagaId(PaymentFixture.DEFAULT_SAGA_ID) } returns null
        every { paymentRepository.findByOrderIdAndStatus(any(), PaymentStatus.PAID) } returns null
        every { paymentRepository.save(any()) } answers { firstArg<Payment>().withId(1L) }

        When("결제를 요청하면") {
            val result = service.pay(payCommand())

            Then("Clock으로 찍은 시각으로 결제 1건을 기록한다") {
                result.paymentId shouldBe 1L
                result.paidAt shouldBe PaymentFixture.FIXED_PAID_AT
                verify(exactly = 1) { paymentRepository.save(match<Payment> { it.status == PaymentStatus.PAID }) }
            }
        }
    }

    Given("결제 이력이 없는 sagaId") {
        val paymentRepository = mockk<PaymentRepository>()
        val service = PaymentService(paymentRepository, PaymentStateMachine(), PaymentFixture.FIXED_CLOCK)
        every { paymentRepository.findBySagaId("saga-none") } returns null

        When("보상을 요청하면") {
            service.cancel(PayCancelCommand(sagaId = "saga-none", orderId = 1L))

            Then("되돌릴 것이 없으므로 아무것도 하지 않고 실패하지 않는다") {
                verify(exactly = 0) { paymentRepository.save(any()) }
            }
        }
    }

    Given("결제된 주문") {
        val paymentRepository = mockk<PaymentRepository>()
        val service = PaymentService(paymentRepository, PaymentStateMachine(), PaymentFixture.FIXED_CLOCK)
        val payment = PaymentFixture.payment().withId(1L)
        every { paymentRepository.findBySagaId(PaymentFixture.DEFAULT_SAGA_ID) } returns payment

        When("보상을 요청하면") {
            service.cancel(PayCancelCommand(sagaId = PaymentFixture.DEFAULT_SAGA_ID, orderId = 1L))

            Then("결제가 CANCELED가 된다") {
                payment.status shouldBe PaymentStatus.CANCELED
            }
        }

        When("보상을 두 번 요청해도") {
            service.cancel(PayCancelCommand(sagaId = PaymentFixture.DEFAULT_SAGA_ID, orderId = 1L))

            Then("CANCELED 그대로다") {
                payment.status shouldBe PaymentStatus.CANCELED
            }
        }
    }

    Given("다른 사가가 이미 결제한 주문") {
        val paymentRepository = mockk<PaymentRepository>()
        val service = PaymentService(paymentRepository, PaymentStateMachine(), PaymentFixture.FIXED_CLOCK)
        every { paymentRepository.findBySagaId("saga-2") } returns null
        every {
            paymentRepository.findByOrderIdAndStatus(PaymentFixture.DEFAULT_ORDER_ID, PaymentStatus.PAID)
        } returns PaymentFixture.payment().withId(1L)

        When("새 사가로 결제를 요청하면") {
            val exception = shouldThrow<BusinessException> { service.pay(payCommand(sagaId = "saga-2")) }

            Then("ALREADY_PAID로 거부하고 외부 승인을 기다리지 않는다") {
                exception.errorCode shouldBe PaymentErrorCode.ALREADY_PAID
                verify(exactly = 0) { paymentRepository.save(any()) }
            }
        }
    }

    Given("취소된 결제만 있는 주문") {
        val paymentRepository = mockk<PaymentRepository>()
        val service = PaymentService(paymentRepository, PaymentStateMachine(), PaymentFixture.FIXED_CLOCK)
        every { paymentRepository.findBySagaId("saga-3") } returns null
        every { paymentRepository.findByOrderIdAndStatus(any(), PaymentStatus.PAID) } returns null
        every { paymentRepository.save(any()) } answers { firstArg<Payment>().withId(2L) }

        When("새 사가로 다시 결제하면") {
            val result = service.pay(payCommand(sagaId = "saga-3"))

            Then("재결제가 허용된다 — 제약은 주문당 PAID 1건이다") {
                result.paymentId shouldBe 2L
            }
        }
    }
})
