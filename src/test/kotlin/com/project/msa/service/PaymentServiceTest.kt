package com.project.msa.service

import com.project.msa.domain.Payment
import com.project.msa.domain.PaymentStatus
import com.project.msa.exception.BusinessException
import com.project.msa.exception.PointErrorCode
import com.project.msa.fixture.OrderFixture
import com.project.msa.fixture.PaymentFixture
import com.project.msa.repository.PaymentRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.Called
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.springframework.dao.DataIntegrityViolationException

class PaymentServiceTest : BehaviorSpec({

    Given("잔액이 400 보다 적은 사용자 1") {
        val paymentRepository = mockk<PaymentRepository>()
        val pointService = mockk<PointService>()
        val service = PaymentService(paymentRepository, pointService, PaymentFixture.FIXED_CLOCK)
        every {
            pointService.use(OrderFixture.DEFAULT_USER_ID, OrderFixture.DEFAULT_TOTAL_PRICE)
        } throws BusinessException(PointErrorCode.INSUFFICIENT_POINT)

        When("주문 10 을 400 결제하면") {
            val exception = shouldThrow<BusinessException> {
                service.pay(OrderFixture.DEFAULT_ORDER_ID, OrderFixture.DEFAULT_USER_ID, OrderFixture.DEFAULT_TOTAL_PRICE)
            }

            Then("AC-4 INSUFFICIENT_POINT 이고 결제를 저장하지 않는다") {
                exception.errorCode shouldBe PointErrorCode.INSUFFICIENT_POINT
                verify { paymentRepository wasNot Called }
            }
        }
    }

    Given("이미 결제 행이 있어 payments.order_id unique 제약에 걸리는 주문 10") {
        val paymentRepository = mockk<PaymentRepository>()
        val pointService = mockk<PointService>()
        val service = PaymentService(paymentRepository, pointService, PaymentFixture.FIXED_CLOCK)
        every { pointService.use(any(), any()) } just Runs
        every { paymentRepository.save(any()) } throws DataIntegrityViolationException("payments.order_id")

        When("400 결제하면") {
            shouldThrow<DataIntegrityViolationException> {
                service.pay(OrderFixture.DEFAULT_ORDER_ID, OrderFixture.DEFAULT_USER_ID, OrderFixture.DEFAULT_TOTAL_PRICE)
            }

            Then("제약 위반 예외가 그대로 전파되고 포인트 차감은 이미 불렀다") {
                verify(exactly = 1) { pointService.use(OrderFixture.DEFAULT_USER_ID, OrderFixture.DEFAULT_TOTAL_PRICE) }
            }
        }
    }

    Given("잔액이 충분한 사용자 1 과 결제 행이 없는 주문 10") {
        val paymentRepository = mockk<PaymentRepository>()
        val pointService = mockk<PointService>()
        val service = PaymentService(paymentRepository, pointService, PaymentFixture.FIXED_CLOCK)
        val savedPayment = slot<Payment>()
        every { pointService.use(any(), any()) } just Runs
        every { paymentRepository.save(capture(savedPayment)) } answers { firstArg() }

        When("400 결제하면") {
            val payment = service.pay(OrderFixture.DEFAULT_ORDER_ID, OrderFixture.DEFAULT_USER_ID, OrderFixture.DEFAULT_TOTAL_PRICE)

            Then("포인트를 차감하고 고정 시각으로 PAID 결제를 저장한다") {
                verify(exactly = 1) { pointService.use(OrderFixture.DEFAULT_USER_ID, OrderFixture.DEFAULT_TOTAL_PRICE) }
                savedPayment.captured.orderId shouldBe OrderFixture.DEFAULT_ORDER_ID
                savedPayment.captured.userId shouldBe OrderFixture.DEFAULT_USER_ID
                savedPayment.captured.amount shouldBe OrderFixture.DEFAULT_TOTAL_PRICE
                savedPayment.captured.paidAt shouldBe PaymentFixture.FIXED_PAID_AT
                payment.status shouldBe PaymentStatus.PAID
            }
        }
    }
})
