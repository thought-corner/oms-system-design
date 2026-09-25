package com.project.order.service

import com.project.common.exception.BusinessException
import com.project.message.order.StockBuyCommand
import com.project.order.domain.OrderSaga
import com.project.order.domain.OrderStatus
import com.project.order.domain.SagaStatus
import com.project.order.domain.SagaStep
import com.project.order.exception.OrderErrorCode
import com.project.order.fixture.CommandFixture
import com.project.order.fixture.OrderFixture
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.slot
import io.mockk.verify
import org.springframework.dao.PessimisticLockingFailureException

private fun SagaHarness.placement(): OrderPlacementService =
    OrderPlacementService(orderRepository, sagaRepository, progress, commandOutbox, OrderFixture.FIXED_CLOCK)

private fun SagaHarness.withKey(key: String, exists: Boolean): SagaHarness = apply {
    every { sagaRepository.existsByOrderIdAndIdempotencyKey(OrderFixture.DEFAULT_ORDER_ID, key) } returns exists
}

private fun SagaHarness.capturingSaga(): io.mockk.CapturingSlot<OrderSaga> {
    val saved = slot<OrderSaga>()
    every { sagaRepository.save(capture(saved)) } answers { saved.captured }
    return saved
}

class OrderPlacementServiceTest : BehaviorSpec({

    listOf(null, "", "   ", "k".repeat(OrderSaga.IDEMPOTENCY_KEY_MAX_LENGTH + 1)).forEach { key ->
        Given("Idempotency-Key 가 '${key?.take(5)}' (길이 ${key?.length}) 인 결제 요청") {
            val h = SagaHarness(order = OrderFixture.order(status = OrderStatus.CREATED))

            When("결제를 시작하면") {
                val exception = shouldThrow<BusinessException> { h.placement().place(CommandFixture.placeOrderCommand(idempotencyKey = key)) }

                Then("B-2 400 INVALID_ORDER 이고 주문을 잠그지도 않는다") {
                    exception.errorCode shouldBe OrderErrorCode.INVALID_ORDER
                    verify(exactly = 0) { h.orderRepository.findWithLockById(any()) }
                    h.saved.shouldBeEmpty()
                }
            }
        }
    }

    Given("없는 주문 999") {
        val h = SagaHarness()
        every { h.orderRepository.findWithLockById(999L) } returns null

        When("결제를 시작하면") {
            val exception = shouldThrow<BusinessException> { h.placement().place(CommandFixture.placeOrderCommand(orderId = 999L)) }

            Then("AC-7 ORDER_NOT_FOUND") {
                exception.errorCode shouldBe OrderErrorCode.ORDER_NOT_FOUND
            }
        }
    }

    Given("다른 요청이 NOWAIT 락을 쥔 주문") {
        val h = SagaHarness()
        every { h.orderRepository.findWithLockById(OrderFixture.DEFAULT_ORDER_ID) } throws
            PessimisticLockingFailureException("NOWAIT")

        When("결제를 시작하면") {
            shouldThrow<PessimisticLockingFailureException> { h.placement().place(CommandFixture.placeOrderCommand()) }

            Then("AC-6 사가도 커맨드도 만들지 않는다") {
                verify(exactly = 0) { h.sagaRepository.save(any()) }
                h.saved.shouldBeEmpty()
            }
        }
    }

    listOf(OrderStatus.CREATED, OrderStatus.FAILED).forEach { status ->
        Given("$status 주문과 처음 보는 키") {
            val h = SagaHarness(order = OrderFixture.order(status = status)).withKey("key-new", exists = false)
            val saga = h.capturingSaga()

            When("결제를 시작하면") {
                val result = h.placement().place(CommandFixture.placeOrderCommand(idempotencyKey = "key-new"))

                Then("B-2 PLACING 으로 바꾸고 새 사가와 STOCK_BUY 를 한 트랜잭션에 넣는다") {
                    result.orderId shouldBe OrderFixture.DEFAULT_ORDER_ID
                    h.order.status shouldBe OrderStatus.PLACING
                    saga.captured.idempotencyKey shouldBe "key-new"
                    saga.captured.status shouldBe SagaStatus.RUNNING
                    saga.captured.currentStep shouldBe SagaStep.STOCK
                    h.messageTypes shouldContainExactly listOf("STOCK_BUY")
                    h.saved.single().sagaId shouldBe saga.captured.sagaId
                    val command = StockBuyCommand.parseFrom(h.payloadOf("STOCK_BUY"))
                    command.sagaId shouldBe saga.captured.sagaId
                    command.itemsList.map { it.productId } shouldContainExactly listOf(1L, 2L)
                    command.itemsList.map { it.quantity } shouldContainExactly listOf(2L, 1L)
                }
            }
        }
    }

    listOf(OrderStatus.PLACING, OrderStatus.COMPLETED, OrderStatus.FAILED).forEach { status ->
        Given("$status 주문과 이미 받은 키") {
            val h = SagaHarness(order = OrderFixture.order(status = status)).withKey("key-1", exists = true)

            When("같은 키로 다시 결제를 요청하면") {
                val result = h.placement().place(CommandFixture.placeOrderCommand(idempotencyKey = "key-1"))

                Then("AC-5 202 로 받되 새 사가도 새 커맨드도 만들지 않고 주문 상태도 그대로다") {
                    result.orderId shouldBe OrderFixture.DEFAULT_ORDER_ID
                    h.order.status shouldBe status
                    verify(exactly = 0) { h.sagaRepository.save(any()) }
                    h.saved.shouldBeEmpty()
                }
            }
        }
    }

    listOf(OrderStatus.PLACING, OrderStatus.COMPLETED).forEach { status ->
        Given("$status 주문과 다른 키") {
            val h = SagaHarness(order = OrderFixture.order(status = status)).withKey("key-other", exists = false)

            When("결제를 요청하면") {
                val exception = shouldThrow<BusinessException> {
                    h.placement().place(CommandFixture.placeOrderCommand(idempotencyKey = "key-other"))
                }

                Then("B-2 409 INVALID_ORDER_STATE_TRANSITION 이고 아무것도 만들지 않는다") {
                    exception.errorCode shouldBe OrderErrorCode.INVALID_ORDER_STATE_TRANSITION
                    h.order.status shouldBe status
                    verify(exactly = 0) { h.sagaRepository.save(any()) }
                    h.saved.shouldBeEmpty()
                }
            }
        }
    }
})
