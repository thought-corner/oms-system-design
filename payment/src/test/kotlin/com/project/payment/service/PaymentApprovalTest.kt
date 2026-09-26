package com.project.payment.service

import com.project.payment.fixture.PaymentFixture
import com.project.payment.fixture.withId
import com.project.payment.repository.PaymentRepository
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.longs.shouldBeLessThan
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify

private fun elapsedMillis(block: () -> Unit): Long {
    val start = System.nanoTime()
    block()
    return (System.nanoTime() - start) / 1_000_000
}

class PaymentApprovalTest : BehaviorSpec({

    Given("결제 이력이 없고 아직 결제되지 않은 주문") {
        val paymentRepository = mockk<PaymentRepository>()
        every { paymentRepository.findBySagaId(PaymentFixture.DEFAULT_SAGA_ID) } returns null
        every { paymentRepository.findByPaidOrderId(PaymentFixture.DEFAULT_ORDER_ID) } returns null

        When("외부 승인을 기다리면") {
            val elapsed = elapsedMillis { PaymentApproval(paymentRepository).await(PaymentFixture.payCommand()) }

            Then("외부 승인 지연만큼 기다린다") {
                elapsed shouldBeGreaterThanOrEqual PaymentApproval.EXTERNAL_PAYMENT_GATEWAY_LATENCY.toMillis()
            }
        }
    }

    Given("같은 sagaId로 이미 결제한 이력이 있는 주문") {
        val paymentRepository = mockk<PaymentRepository>()
        every { paymentRepository.findBySagaId(PaymentFixture.DEFAULT_SAGA_ID) } returns PaymentFixture.payment().withId(7L)

        When("외부 승인을 기다리면") {
            val elapsed = elapsedMillis { PaymentApproval(paymentRepository).await(PaymentFixture.payCommand()) }

            Then("재전달·재발행된 커맨드이므로 기다리지 않는다") {
                elapsed shouldBeLessThan PaymentApproval.EXTERNAL_PAYMENT_GATEWAY_LATENCY.toMillis()
                verify(exactly = 0) { paymentRepository.findByPaidOrderId(any()) }
            }
        }
    }

    Given("다른 사가가 이미 결제한 주문") {
        val paymentRepository = mockk<PaymentRepository>()
        every { paymentRepository.findBySagaId("saga-2") } returns null
        every { paymentRepository.findByPaidOrderId(PaymentFixture.DEFAULT_ORDER_ID) } returns PaymentFixture.payment().withId(1L)

        When("외부 승인을 기다리면") {
            val elapsed = elapsedMillis { PaymentApproval(paymentRepository).await(PaymentFixture.payCommand(sagaId = "saga-2")) }

            Then("곧 ALREADY_PAID로 거부될 결제이므로 기다리지 않는다") {
                elapsed shouldBeLessThan PaymentApproval.EXTERNAL_PAYMENT_GATEWAY_LATENCY.toMillis()
            }
        }
    }
})
