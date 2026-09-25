package com.project.payment.service

import com.project.common.exception.BusinessException
import com.project.payment.domain.Payment
import com.project.payment.domain.PaymentStatus
import com.project.payment.domain.SagaGuardKind
import com.project.payment.exception.PaymentErrorCode
import com.project.payment.fixture.PaymentFixture
import com.project.payment.fixture.withId
import com.project.payment.repository.PaymentRepository
import com.project.payment.repository.SagaGuardRepository
import com.project.payment.service.dto.PayCancelCommand
import com.project.payment.service.dto.PayCommand
import com.project.payment.service.dto.PaymentMessageType
import com.project.payment.statemachine.PaymentStateMachine
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.Called
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.springframework.dao.DataIntegrityViolationException
import java.sql.SQLIntegrityConstraintViolationException

private fun payCommand(sagaId: String = PaymentFixture.DEFAULT_SAGA_ID) =
    PayCommand(
        sagaId = sagaId,
        orderId = PaymentFixture.DEFAULT_ORDER_ID,
        userId = PaymentFixture.DEFAULT_USER_ID,
        amount = PaymentFixture.DEFAULT_AMOUNT,
    )

private fun guardRepository(kind: SagaGuardKind = SagaGuardKind.FORWARD): SagaGuardRepository =
    mockk<SagaGuardRepository>().also {
        every { it.insertIfAbsent(any(), any(), any()) } just Runs
        every { it.findWithLockBySagaId(any()) } answers { PaymentFixture.guard(sagaId = firstArg(), kind = kind) }
    }

private fun sagaReplyOutbox(): SagaReplyOutbox =
    mockk<SagaReplyOutbox>().also {
        every { it.succeeded(any(), any(), any()) } just Runs
        every { it.failed(any(), any(), any(), any()) } just Runs
    }

private fun paymentService(
    paymentRepository: PaymentRepository,
    guardRepository: SagaGuardRepository = guardRepository(),
    sagaReplyOutbox: SagaReplyOutbox = sagaReplyOutbox(),
): PaymentService =
    PaymentService(
        paymentRepository,
        SagaGuardLock(guardRepository, PaymentFixture.FIXED_CLOCK),
        PaymentStateMachine(),
        sagaReplyOutbox,
        PaymentFixture.FIXED_CLOCK,
    )

class PaymentServiceTest : BehaviorSpec({

    Given("같은 sagaId로 이미 결제한 이력이 있는 주문") {
        val paymentRepository = mockk<PaymentRepository>()
        val sagaReplyOutbox = sagaReplyOutbox()
        val service = paymentService(paymentRepository, sagaReplyOutbox = sagaReplyOutbox)
        every { paymentRepository.findBySagaId(PaymentFixture.DEFAULT_SAGA_ID) } returns PaymentFixture.payment().withId(7L)

        When("같은 sagaId로 결제를 다시 요청하면") {
            val result = service.pay(payCommand())

            Then("외부 승인을 기다리지 않고 첫 번째 결제를 그대로 돌려주며 성공 응답을 다시 남긴다") {
                result.paymentId shouldBe 7L
                result.paidAt shouldBe PaymentFixture.FIXED_PAID_AT
                verify(exactly = 0) { paymentRepository.save(any()) }
                verify(exactly = 1) {
                    sagaReplyOutbox.succeeded(
                        PaymentMessageType.PAYMENT_PAY,
                        PaymentFixture.DEFAULT_SAGA_ID,
                        PaymentFixture.DEFAULT_ORDER_ID,
                    )
                }
            }
        }
    }

    Given("결제 이력이 없는 주문") {
        val paymentRepository = mockk<PaymentRepository>()
        val guardRepository = guardRepository()
        val sagaReplyOutbox = sagaReplyOutbox()
        val service = paymentService(paymentRepository, guardRepository, sagaReplyOutbox)
        every { paymentRepository.findBySagaId(PaymentFixture.DEFAULT_SAGA_ID) } returns null
        every { paymentRepository.findByPaidOrderId(any()) } returns null
        every { paymentRepository.save(any()) } answers { firstArg<Payment>().withId(1L) }

        When("결제를 요청하면") {
            val result = service.pay(payCommand())

            Then("사가 가드를 FORWARD로 잡은 뒤 Clock으로 찍은 시각으로 결제 1건을 기록하고 같은 트랜잭션에서 성공 응답을 남긴다") {
                result.paymentId shouldBe 1L
                verifyOrder {
                    guardRepository.insertIfAbsent(PaymentFixture.DEFAULT_SAGA_ID, SagaGuardKind.FORWARD.name, any())
                    guardRepository.findWithLockBySagaId(PaymentFixture.DEFAULT_SAGA_ID)
                    paymentRepository.findBySagaId(PaymentFixture.DEFAULT_SAGA_ID)
                    paymentRepository.findByPaidOrderId(PaymentFixture.DEFAULT_ORDER_ID)
                    paymentRepository.save(any())
                    sagaReplyOutbox.succeeded(
                        PaymentMessageType.PAYMENT_PAY,
                        PaymentFixture.DEFAULT_SAGA_ID,
                        PaymentFixture.DEFAULT_ORDER_ID,
                    )
                }
                result.paidAt shouldBe PaymentFixture.FIXED_PAID_AT
                verify(exactly = 1) { paymentRepository.save(match<Payment> { it.status == PaymentStatus.PAID }) }
            }
        }
    }

    Given("결제 이력이 없는 sagaId") {
        val paymentRepository = mockk<PaymentRepository>()
        val guardRepository = guardRepository(SagaGuardKind.CANCEL)
        val sagaReplyOutbox = sagaReplyOutbox()
        val service = paymentService(paymentRepository, guardRepository, sagaReplyOutbox)
        every { paymentRepository.findBySagaId("saga-none") } returns null

        When("보상을 요청하면") {
            service.cancel(PayCancelCommand(sagaId = "saga-none", orderId = 1L))

            Then("CANCEL 가드만 남기고 실패하지 않으며 빈 결과로 성공 응답을 남긴다") {
                verifyOrder {
                    guardRepository.insertIfAbsent("saga-none", SagaGuardKind.CANCEL.name, any())
                    guardRepository.findWithLockBySagaId("saga-none")
                    paymentRepository.findBySagaId("saga-none")
                    sagaReplyOutbox.succeeded(PaymentMessageType.PAYMENT_CANCEL, "saga-none", 1L)
                }
                verify(exactly = 0) { paymentRepository.save(any()) }
            }
        }
    }

    Given("결제된 주문") {
        val paymentRepository = mockk<PaymentRepository>()
        val sagaReplyOutbox = sagaReplyOutbox()
        val service = paymentService(paymentRepository, sagaReplyOutbox = sagaReplyOutbox)
        val payment = PaymentFixture.payment().withId(1L)
        every { paymentRepository.findBySagaId(PaymentFixture.DEFAULT_SAGA_ID) } returns payment

        When("보상을 요청하면") {
            service.cancel(PayCancelCommand(sagaId = PaymentFixture.DEFAULT_SAGA_ID, orderId = 1L))

            Then("결제가 CANCELED가 되고 주문당 PAID 제약에서 빠진다") {
                payment.status shouldBe PaymentStatus.CANCELED
                payment.paidOrderId.shouldBeNull()
            }
        }

        When("보상을 두 번 요청해도") {
            service.cancel(PayCancelCommand(sagaId = PaymentFixture.DEFAULT_SAGA_ID, orderId = 1L))

            Then("CANCELED 그대로이고 보상 응답은 매번 성공이다") {
                payment.status shouldBe PaymentStatus.CANCELED
                verify(exactly = 2) {
                    sagaReplyOutbox.succeeded(PaymentMessageType.PAYMENT_CANCEL, PaymentFixture.DEFAULT_SAGA_ID, 1L)
                }
            }
        }
    }

    Given("다른 사가가 이미 결제한 주문") {
        val paymentRepository = mockk<PaymentRepository>()
        val sagaReplyOutbox = sagaReplyOutbox()
        val service = paymentService(paymentRepository, sagaReplyOutbox = sagaReplyOutbox)
        every { paymentRepository.findBySagaId("saga-2") } returns null
        every {
            paymentRepository.findByPaidOrderId(PaymentFixture.DEFAULT_ORDER_ID)
        } returns PaymentFixture.payment().withId(1L)

        When("새 사가로 결제를 요청하면") {
            val exception = shouldThrow<BusinessException> { service.pay(payCommand(sagaId = "saga-2")) }

            Then("ALREADY_PAID로 거부하고 외부 승인을 기다리지 않으며 이 트랜잭션에는 응답을 남기지 않는다") {
                exception.errorCode shouldBe PaymentErrorCode.ALREADY_PAID
                verify(exactly = 0) { paymentRepository.save(any()) }
                verify { sagaReplyOutbox wasNot Called }
            }
        }
    }

    Given("보상이 먼저 도착해 CANCEL 가드가 남은 sagaId") {
        val paymentRepository = mockk<PaymentRepository>()
        val sagaReplyOutbox = sagaReplyOutbox()
        val service = paymentService(paymentRepository, guardRepository(SagaGuardKind.CANCEL), sagaReplyOutbox)

        When("늦게 도착한 결제 요청이 오면") {
            val exception = shouldThrow<BusinessException> { service.pay(payCommand()) }

            Then("SAGA_ALREADY_COMPENSATED로 거부하고 결제 이력을 읽지도 기록하지도 않는다") {
                exception.errorCode shouldBe PaymentErrorCode.SAGA_ALREADY_COMPENSATED
                verify { paymentRepository wasNot Called }
                verify { sagaReplyOutbox wasNot Called }
            }
        }
    }

    Given("같은 sagaId의 결제가 이미 취소된 주문") {
        val paymentRepository = mockk<PaymentRepository>()
        val service = paymentService(paymentRepository)
        val canceled = PaymentFixture.payment().withId(1L).also { it.transitionTo(PaymentStatus.CANCELED) }
        every { paymentRepository.findBySagaId(PaymentFixture.DEFAULT_SAGA_ID) } returns canceled

        When("같은 sagaId로 결제 요청이 다시 오면") {
            val exception = shouldThrow<BusinessException> { service.pay(payCommand()) }

            Then("취소된 결제를 성공으로 돌려주지 않고 SAGA_ALREADY_COMPENSATED로 거부한다") {
                exception.errorCode shouldBe PaymentErrorCode.SAGA_ALREADY_COMPENSATED
                verify(exactly = 0) { paymentRepository.save(any()) }
            }
        }
    }

    Given("외부 승인을 기다리는 동안 다른 사가가 같은 주문을 먼저 결제한 상태") {
        val paymentRepository = mockk<PaymentRepository>()
        val sagaReplyOutbox = sagaReplyOutbox()
        val service = paymentService(paymentRepository, sagaReplyOutbox = sagaReplyOutbox)
        every { paymentRepository.findBySagaId("saga-4") } returns null
        every { paymentRepository.findByPaidOrderId(any()) } returns null
        every { paymentRepository.save(any()) } throws DataIntegrityViolationException(
            "duplicate",
            SQLIntegrityConstraintViolationException(
                "Duplicate entry '1' for key 'payments.${Payment.UK_PAID_ORDER_ID}'",
            ),
        )

        When("결제를 기록하면") {
            val exception = shouldThrow<BusinessException> { service.pay(payCommand(sagaId = "saga-4")) }

            Then("paid_order_id unique 위반을 ALREADY_PAID로 번역하고 성공 응답을 남기지 않는다") {
                exception.errorCode shouldBe PaymentErrorCode.ALREADY_PAID
                verify { sagaReplyOutbox wasNot Called }
            }
        }
    }

    Given("비즈니스 판정으로 롤백된 결제") {
        val paymentRepository = mockk<PaymentRepository>()
        val sagaReplyOutbox = sagaReplyOutbox()
        val service = paymentService(paymentRepository, sagaReplyOutbox = sagaReplyOutbox)

        When("실패 응답을 기록하면") {
            service.recordPayFailure(payCommand(), PaymentErrorCode.ALREADY_PAID)

            Then("가드도 결제도 건드리지 않고 FAILED 응답만 남긴다") {
                verify(exactly = 1) {
                    sagaReplyOutbox.failed(
                        PaymentMessageType.PAYMENT_PAY,
                        PaymentFixture.DEFAULT_SAGA_ID,
                        PaymentFixture.DEFAULT_ORDER_ID,
                        PaymentErrorCode.ALREADY_PAID,
                    )
                }
                verify { paymentRepository wasNot Called }
            }
        }
    }
})
