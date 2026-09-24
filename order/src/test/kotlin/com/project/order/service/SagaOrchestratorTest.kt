package com.project.order.service

import com.project.order.client.AlertSender
import com.project.order.client.CompensationFailedAlert
import com.project.order.client.PaymentApiClient
import com.project.order.client.PointApiClient
import com.project.order.client.ProductApiClient
import com.project.order.client.dto.BuyApiRequest
import com.project.order.client.dto.BuyCancelApiRequest
import com.project.order.client.dto.PayApiRequest
import com.project.order.client.dto.PayApiResponse
import com.project.order.client.dto.PayCancelApiRequest
import com.project.order.client.dto.UseApiRequest
import com.project.order.client.dto.UseCancelApiRequest
import com.project.common.exception.BusinessException
import com.project.order.exception.PointErrorCode
import com.project.order.exception.ProductErrorCode
import com.project.order.fixture.CommandFixture
import com.project.order.fixture.OrderFixture
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkClass
import io.mockk.verify
import io.mockk.verifyOrder

private class Fixture(
    val sagaState: OrderSagaStateService = mockk(relaxed = true),
    val product: ProductApiClient = mockk(relaxed = true),
    val point: PointApiClient = mockk(relaxed = true),
    val payment: PaymentApiClient = mockk(relaxed = true),
    val alertSender: AlertSender = mockk(relaxed = true),
) {
    val orchestrator = SagaOrchestrator(sagaState, product, point, payment, alertSender)
}

class SagaOrchestratorTest : BehaviorSpec({

    Given("이미 결제가 끝난 주문") {
        val f = Fixture()
        every { f.sagaState.start(OrderFixture.DEFAULT_ORDER_ID) } returns null

        When("같은 주문으로 결제를 다시 요청하면") {
            f.orchestrator.placeOrder(CommandFixture.placeOrderCommand())

            Then("어느 단계도 호출하지 않는다") {
                verify(exactly = 0) { f.product.buy(any()) }
                verify(exactly = 0) { f.point.use(any()) }
                verify(exactly = 0) { f.payment.pay(any()) }
            }
        }
    }

    Given("세 단계가 모두 성공하는 주문") {
        val f = Fixture()
        every { f.sagaState.start(OrderFixture.DEFAULT_ORDER_ID) } returns OrderFixture.context()
        every { f.product.buy(any()) } returns OrderFixture.DEFAULT_TOTAL_PRICE
        every { f.point.use(any()) } just Runs
        every { f.payment.pay(any()) } returns PayApiResponse(paymentId = 1L, paidAt = OrderFixture.FIXED_TIME)

        When("결제를 요청하면") {
            f.orchestrator.placeOrder(CommandFixture.placeOrderCommand())

            Then("재고·포인트·결제 순으로 부르고 단계마다 사가에 기록한다") {
                verifyOrder {
                    f.product.buy(any())
                    f.sagaState.stockCompleted(OrderFixture.DEFAULT_SAGA_ID, OrderFixture.DEFAULT_TOTAL_PRICE)
                    f.point.use(any())
                    f.sagaState.pointCompleted(OrderFixture.DEFAULT_SAGA_ID)
                    f.payment.pay(any())
                    f.sagaState.paymentCompleted(OrderFixture.DEFAULT_SAGA_ID)
                    f.sagaState.succeed(OrderFixture.DEFAULT_SAGA_ID, OrderFixture.DEFAULT_ORDER_ID)
                }
            }

            Then("보상은 한 번도 부르지 않는다") {
                verify(exactly = 0) { f.product.cancel(any(), any()) }
                verify(exactly = 0) { f.point.cancel(any(), any()) }
                verify(exactly = 0) { f.payment.cancel(any(), any()) }
            }

            Then("재고 차감이 돌려준 총액으로 포인트와 결제를 부른다") {
                verify {
                    f.point.use(
                        UseApiRequest(
                            OrderFixture.DEFAULT_SAGA_ID,
                            OrderFixture.DEFAULT_ORDER_ID,
                            OrderFixture.DEFAULT_USER_ID,
                            OrderFixture.DEFAULT_TOTAL_PRICE,
                        ),
                    )
                }
                verify {
                    f.payment.pay(
                        PayApiRequest(
                            OrderFixture.DEFAULT_SAGA_ID,
                            OrderFixture.DEFAULT_ORDER_ID,
                            OrderFixture.DEFAULT_USER_ID,
                            OrderFixture.DEFAULT_TOTAL_PRICE,
                        ),
                    )
                }
            }
        }
    }

    Given("세 단계가 모두 성공했지만 성공 기록이 주문 행 경합으로 실패한 주문") {
        val f = Fixture()
        every { f.sagaState.start(OrderFixture.DEFAULT_ORDER_ID) } returns OrderFixture.context()
        every { f.product.buy(any()) } returns OrderFixture.DEFAULT_TOTAL_PRICE
        every { f.point.use(any()) } just Runs
        every { f.payment.pay(any()) } returns PayApiResponse(paymentId = 1L, paidAt = OrderFixture.FIXED_TIME)
        every { f.sagaState.succeed(any(), any()) } throws IllegalStateException("lock wait timeout")

        When("결제를 요청하면") {
            shouldThrow<IllegalStateException> { f.orchestrator.placeOrder(CommandFixture.placeOrderCommand()) }

            Then("성공한 세 단계를 보상하지 않는다 — 사가는 RUNNING으로 남아 워커가 전진 복구한다") {
                verify(exactly = 0) { f.sagaState.beginCompensation(any(), any()) }
                verify(exactly = 0) { f.payment.cancel(any(), any()) }
                verify(exactly = 0) { f.point.cancel(any(), any()) }
                verify(exactly = 0) { f.product.cancel(any(), any()) }
            }
        }
    }

    Given("재고가 부족한 주문") {
        val f = Fixture()
        every { f.sagaState.start(OrderFixture.DEFAULT_ORDER_ID) } returns OrderFixture.context()
        every { f.product.buy(any()) } throws BusinessException(ProductErrorCode.INSUFFICIENT_STOCK)

        When("결제를 요청하면") {
            val exception = shouldThrow<BusinessException> {
                f.orchestrator.placeOrder(CommandFixture.placeOrderCommand())
            }

            Then("INSUFFICIENT_STOCK을 그대로 올리고 다음 단계로 가지 않는다") {
                exception.errorCode shouldBe ProductErrorCode.INSUFFICIENT_STOCK
                verify(exactly = 0) { f.point.use(any()) }
                verify(exactly = 0) { f.payment.pay(any()) }
            }

            Then("보상이 성공했으므로 알림은 보내지 않는다") {
                verify(exactly = 0) { f.alertSender.send(any<CompensationFailedAlert>()) }
            }

            Then("보상은 무조건 세 곳에 보내고 사가를 닫는다 — 안 한 단계는 참여자가 0으로 답한다") {
                verify(exactly = 1) { f.payment.cancel(any(), any()) }
                verify(exactly = 1) { f.point.cancel(any(), any()) }
                verify(exactly = 1) { f.product.cancel(any(), any()) }
                verify(exactly = 1) { f.sagaState.compensated(OrderFixture.DEFAULT_SAGA_ID, OrderFixture.DEFAULT_ORDER_ID) }
            }
        }
    }

    Given("재고는 되지만 잔액이 부족한 주문") {
        val f = Fixture()
        every { f.sagaState.start(OrderFixture.DEFAULT_ORDER_ID) } returns OrderFixture.context()
        every { f.product.buy(any()) } returns OrderFixture.DEFAULT_TOTAL_PRICE
        every { f.point.use(any()) } throws BusinessException(PointErrorCode.INSUFFICIENT_POINT)

        When("결제를 요청하면") {
            val exception = shouldThrow<BusinessException> {
                f.orchestrator.placeOrder(CommandFixture.placeOrderCommand())
            }

            Then("INSUFFICIENT_POINT를 올리고 결제 단계로 가지 않는다") {
                exception.errorCode shouldBe PointErrorCode.INSUFFICIENT_POINT
                verify(exactly = 0) { f.payment.pay(any()) }
            }

            Then("보상을 세 곳에 보내고 사가를 닫는다") {
                verify(exactly = 1) {
                    f.product.cancel(BuyCancelApiRequest(OrderFixture.DEFAULT_SAGA_ID, OrderFixture.DEFAULT_ORDER_ID), any())
                }
                verify(exactly = 1) { f.point.cancel(any(), any()) }
                verify(exactly = 1) { f.sagaState.compensated(OrderFixture.DEFAULT_SAGA_ID, OrderFixture.DEFAULT_ORDER_ID) }
            }
        }
    }

    Given("재고와 포인트는 됐는데 결제가 거절된 주문") {
        val f = Fixture()
        every { f.sagaState.start(OrderFixture.DEFAULT_ORDER_ID) } returns OrderFixture.context()
        every { f.product.buy(any()) } returns OrderFixture.DEFAULT_TOTAL_PRICE
        every { f.point.use(any()) } just Runs
        every { f.payment.pay(any()) } throws IllegalStateException("gateway declined")

        When("결제를 요청하면") {
            shouldThrow<IllegalStateException> { f.orchestrator.placeOrder(CommandFixture.placeOrderCommand()) }

            Then("포인트 환불 다음 재고 복구 순으로 되돌린다") {
                verifyOrder {
                    f.point.cancel(UseCancelApiRequest(OrderFixture.DEFAULT_SAGA_ID, OrderFixture.DEFAULT_ORDER_ID), any())
                    f.product.cancel(BuyCancelApiRequest(OrderFixture.DEFAULT_SAGA_ID, OrderFixture.DEFAULT_ORDER_ID), any())
                }
            }

            Then("결제 취소도 함께 보낸다 — 결제가 실제로 됐는지 오케스트레이터는 모른다") {
                verify(exactly = 1) { f.payment.cancel(any(), any()) }
                verify(exactly = 1) { f.sagaState.compensated(OrderFixture.DEFAULT_SAGA_ID, OrderFixture.DEFAULT_ORDER_ID) }
            }
        }
    }

    Given("보상 호출마저 실패하는 주문") {
        val f = Fixture()
        every { f.sagaState.start(OrderFixture.DEFAULT_ORDER_ID) } returns OrderFixture.context()
        every { f.product.buy(any()) } returns OrderFixture.DEFAULT_TOTAL_PRICE
        every { f.point.use(any()) } throws BusinessException(PointErrorCode.INSUFFICIENT_POINT)
        every { f.product.cancel(any(), any()) } throws IllegalStateException("product down")

        When("결제를 요청하면") {
            shouldThrow<BusinessException> { f.orchestrator.placeOrder(CommandFixture.placeOrderCommand()) }

            Then("사가를 COMPENSATION_FAILED로 남기고 주문을 FAILED로 닫지 않는다") {
                verify(exactly = 1) { f.sagaState.compensationFailed(OrderFixture.DEFAULT_SAGA_ID, "product down") }
                verify(exactly = 0) { f.sagaState.compensated(any(), any()) }
            }

            Then("사람이 개입하도록 알림을 보낸다") {
                verify(exactly = 1) { f.alertSender.send(any<CompensationFailedAlert>()) }
            }
        }
    }
})
