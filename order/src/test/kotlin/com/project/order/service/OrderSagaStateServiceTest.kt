package com.project.order.service

import com.project.common.exception.BusinessException
import com.project.order.domain.Order
import com.project.order.domain.OrderSaga
import com.project.order.domain.OrderStatus
import com.project.order.domain.SagaStatus
import com.project.order.exception.OrderErrorCode
import com.project.order.fixture.OrderFixture
import com.project.order.repository.OrderItemRepository
import com.project.order.repository.OrderRepository
import com.project.order.repository.OrderSagaRepository
import com.project.order.statemachine.OrderStateMachine
import com.project.order.statemachine.SagaStateMachine
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify

private class SagaStateFixture(
    val orderRepository: OrderRepository = mockk(),
    val orderItemRepository: OrderItemRepository = mockk(),
    val sagaRepository: OrderSagaRepository = mockk(),
) {
    val sagaState = OrderSagaStateService(
        orderRepository,
        orderItemRepository,
        sagaRepository,
        OrderStateMachine(),
        SagaStateMachine(),
        OrderFixture.FIXED_CLOCK,
    )

    fun withOrder(order: Order) = apply {
        every { orderRepository.findWithLockById(OrderFixture.DEFAULT_ORDER_ID) } returns order
        every { orderRepository.findWithWaitingLockById(OrderFixture.DEFAULT_ORDER_ID) } returns order
    }

    fun withSaga(saga: OrderSaga) = apply {
        every { sagaRepository.findBySagaId(OrderFixture.DEFAULT_SAGA_ID) } returns saga
        every { sagaRepository.findWithWaitingLockBySagaId(OrderFixture.DEFAULT_SAGA_ID) } returns saga
        every { sagaRepository.save(any()) } answers { firstArg() }
    }
}

class OrderSagaStateServiceTest : BehaviorSpec({

    Given("존재하지 않는 주문") {
        val f = SagaStateFixture()
        every { f.orderRepository.findWithLockById(OrderFixture.DEFAULT_ORDER_ID) } returns null

        When("사가를 시작하면") {
            val exception = shouldThrow<BusinessException> { f.sagaState.start(OrderFixture.DEFAULT_ORDER_ID) }

            Then("ORDER_NOT_FOUND") {
                exception.errorCode shouldBe OrderErrorCode.ORDER_NOT_FOUND
                exception.message shouldContain "orderId=${OrderFixture.DEFAULT_ORDER_ID}"
            }
        }
    }

    Given("이미 결제가 끝난 주문") {
        val f = SagaStateFixture().withOrder(OrderFixture.order(status = OrderStatus.COMPLETED))

        When("사가를 시작하면") {
            val context = f.sagaState.start(OrderFixture.DEFAULT_ORDER_ID)

            Then("null을 돌려주고 사가를 만들지 않는다") {
                context.shouldBeNull()
                verify(exactly = 0) { f.sagaRepository.save(any()) }
            }
        }
    }

    Given("CREATED인 주문과 productId 역순 항목") {
        val order = OrderFixture.order(status = OrderStatus.CREATED)
        val f = SagaStateFixture().withOrder(order)
        every { f.orderItemRepository.findAllByOrderId(OrderFixture.DEFAULT_ORDER_ID) } returns
            OrderFixture.itemsInReverseProductOrder()
        every { f.sagaRepository.save(any()) } answers { firstArg() }

        When("사가를 시작하면") {
            val context = f.sagaState.start(OrderFixture.DEFAULT_ORDER_ID)

            Then("주문이 PLACING이 되고 사가가 생기며 항목은 productId 오름차순이다") {
                order.status shouldBe OrderStatus.PLACING
                order.updatedAt shouldBe OrderFixture.FIXED_TIME
                context!!.orderId shouldBe OrderFixture.DEFAULT_ORDER_ID
                context.userId shouldBe OrderFixture.DEFAULT_USER_ID
                context.items.map { it.productId } shouldBe listOf(1L, 2L)
                context.sagaId.isNotBlank() shouldBe true
            }
        }
    }

    Given("FAILED인 주문") {
        val order = OrderFixture.order(status = OrderStatus.FAILED)
        val f = SagaStateFixture().withOrder(order)
        every { f.orderItemRepository.findAllByOrderId(any()) } returns emptyList()
        every { f.sagaRepository.save(any()) } answers { firstArg() }

        When("사가를 다시 시작하면") {
            f.sagaState.start(OrderFixture.DEFAULT_ORDER_ID)

            Then("재결제가 허용되어 PLACING으로 간다") {
                order.status shouldBe OrderStatus.PLACING
            }
        }
    }

    Given("PLACING인 주문") {
        val f = SagaStateFixture().withOrder(OrderFixture.order(status = OrderStatus.PLACING))

        When("사가를 또 시작하면") {
            val exception = shouldThrow<BusinessException> { f.sagaState.start(OrderFixture.DEFAULT_ORDER_ID) }

            Then("진행 중인 사가가 있으므로 전이가 거부된다") {
                exception.errorCode shouldBe OrderErrorCode.INVALID_ORDER_STATE_TRANSITION
            }
        }
    }

    Given("진행 중인 사가") {
        val saga = OrderFixture.saga()
        val f = SagaStateFixture().withSaga(saga)

        When("단계를 차례로 기록하면") {
            f.sagaState.stockCompleted(OrderFixture.DEFAULT_SAGA_ID, OrderFixture.DEFAULT_TOTAL_PRICE)
            f.sagaState.pointCompleted(OrderFixture.DEFAULT_SAGA_ID)
            f.sagaState.paymentCompleted(OrderFixture.DEFAULT_SAGA_ID)

            Then("세 단계가 성공으로 남고 총액이 기록된다") {
                saga.stockDone shouldBe true
                saga.pointDone shouldBe true
                saga.paymentDone shouldBe true
                saga.totalPrice shouldBe OrderFixture.DEFAULT_TOTAL_PRICE
            }

            Then("사가 행을 잠금 아래에서 읽어 보상이 커밋한 상태를 옛 값으로 덮어쓰지 않는다") {
                verify(exactly = 3) { f.sagaRepository.findWithWaitingLockBySagaId(OrderFixture.DEFAULT_SAGA_ID) }
                verify(exactly = 0) { f.sagaRepository.findBySagaId(any()) }
            }
        }
    }

    Given("존재하지 않는 사가") {
        val f = SagaStateFixture()
        every { f.sagaRepository.findWithWaitingLockBySagaId("saga-none") } returns null

        When("단계를 기록하면") {
            val exception = shouldThrow<BusinessException> { f.sagaState.stockCompleted("saga-none", 1L) }

            Then("ORDER_NOT_FOUND이고 sagaId가 메시지에 담긴다") {
                exception.errorCode shouldBe OrderErrorCode.ORDER_NOT_FOUND
                exception.message shouldContain "sagaId=saga-none"
            }
        }
    }

    Given("세 단계가 끝난 PLACING 주문") {
        val order = OrderFixture.order(status = OrderStatus.PLACING)
        val saga = OrderFixture.saga()
        val f = SagaStateFixture().withOrder(order).withSaga(saga)

        When("성공으로 닫으면") {
            f.sagaState.succeed(OrderFixture.DEFAULT_SAGA_ID, OrderFixture.DEFAULT_ORDER_ID)

            Then("주문 행을 NOWAIT가 아니라 대기형 잠금으로 읽어 주문은 COMPLETED, 사가는 SUCCEEDED") {
                order.status shouldBe OrderStatus.COMPLETED
                saga.status shouldBe SagaStatus.SUCCEEDED
                verify(exactly = 1) { f.orderRepository.findWithWaitingLockById(OrderFixture.DEFAULT_ORDER_ID) }
                verify(exactly = 0) { f.orderRepository.findWithLockById(any()) }
            }
        }
    }

    Given("재고와 포인트까지 성공한 사가") {
        val saga = OrderFixture.saga()
        saga.stockCompleted(OrderFixture.DEFAULT_TOTAL_PRICE, OrderFixture.FIXED_TIME)
        saga.pointCompleted(OrderFixture.FIXED_TIME)
        val f = SagaStateFixture().withSaga(saga)

        When("보상을 시작하면") {
            f.sagaState.beginCompensation(OrderFixture.DEFAULT_SAGA_ID, "잔액이 부족합니다.")

            Then("COMPENSATING이 되고 실패 사유가 남는다") {
                saga.status shouldBe SagaStatus.COMPENSATING
                saga.lastError shouldBe "잔액이 부족합니다."
            }
        }

        When("이미 COMPENSATING인 사가에 보상을 다시 시작하면") {
            f.sagaState.beginCompensation(OrderFixture.DEFAULT_SAGA_ID, "두 번째")

            Then("전이를 다시 걸지 않고 사유만 갱신한다") {
                saga.status shouldBe SagaStatus.COMPENSATING
                saga.lastError shouldBe "두 번째"
            }
        }
    }

    Given("보상 중인 사가와 PLACING 주문") {
        val order = OrderFixture.order(status = OrderStatus.PLACING)
        val saga = OrderFixture.saga()
        saga.stockCompleted(OrderFixture.DEFAULT_TOTAL_PRICE, OrderFixture.FIXED_TIME)
        saga.transitionTo(SagaStatus.COMPENSATING, OrderFixture.FIXED_TIME)
        val f = SagaStateFixture().withOrder(order).withSaga(saga)

        When("보상이 끝나면") {
            f.sagaState.compensated(OrderFixture.DEFAULT_SAGA_ID, OrderFixture.DEFAULT_ORDER_ID)

            Then("대기형 잠금으로 읽어 주문은 FAILED, 사가는 COMPENSATED이고 성공 단계 표시는 그대로 남는다") {
                verify(exactly = 0) { f.orderRepository.findWithLockById(any()) }
                order.status shouldBe OrderStatus.FAILED
                saga.status shouldBe SagaStatus.COMPENSATED
                saga.stockDone shouldBe true
            }
        }
    }

    Given("보상 중인 사가") {
        val saga = OrderFixture.saga()
        saga.transitionTo(SagaStatus.COMPENSATING, OrderFixture.FIXED_TIME)
        val f = SagaStateFixture().withSaga(saga)

        When("보상이 실패하면") {
            f.sagaState.compensationFailed(OrderFixture.DEFAULT_SAGA_ID, "product down")

            Then("COMPENSATION_FAILED로 남고 시도 횟수와 오류가 기록된다") {
                saga.status shouldBe SagaStatus.COMPENSATION_FAILED
                saga.attempts shouldBe 1
                saga.lastError shouldBe "product down"
            }
        }
    }

    Given("다른 워커가 이미 집었거나 더 이상 멈춰 있지 않은 사가") {
        val f = SagaStateFixture()
        every {
            f.sagaRepository.findWithLockBySagaIdAndStatusInAndUpdatedAtLessThan(OrderFixture.DEFAULT_SAGA_ID, any(), any())
        } returns null

        When("집으려 하면") {
            val claimed = f.sagaState.claim(OrderFixture.DEFAULT_SAGA_ID)

            Then("null을 돌려줘 이번 스윕에서 건너뛴다") {
                claimed.shouldBeNull()
            }
        }
    }

    Given("60초 넘게 멈춘 RUNNING 사가") {
        val saga = OrderFixture.saga(createdAt = OrderFixture.FIXED_TIME.minusMinutes(5))
        val f = SagaStateFixture()
        every {
            f.sagaRepository.findWithLockBySagaIdAndStatusInAndUpdatedAtLessThan(
                OrderFixture.DEFAULT_SAGA_ID,
                any(),
                OrderFixture.FIXED_TIME.minusSeconds(60),
            )
        } returns saga

        When("집으면") {
            val claimed = f.sagaState.claim(OrderFixture.DEFAULT_SAGA_ID)

            Then("updated_at을 지금으로 갱신해 다른 워커의 임계에서 빠지고 상태를 돌려준다") {
                claimed?.status shouldBe SagaStatus.RUNNING
                claimed?.paymentDone shouldBe false
                saga.updatedAt shouldBe OrderFixture.FIXED_TIME
            }
        }
    }

    Given("결제까지 성공한 채 60초 넘게 멈춘 RUNNING 사가") {
        val saga = OrderFixture.saga(createdAt = OrderFixture.FIXED_TIME.minusMinutes(5))
        saga.paymentCompleted(OrderFixture.FIXED_TIME.minusMinutes(5))
        val f = SagaStateFixture()
        every {
            f.sagaRepository.findWithLockBySagaIdAndStatusInAndUpdatedAtLessThan(OrderFixture.DEFAULT_SAGA_ID, any(), any())
        } returns saga

        When("집으면") {
            val claimed = f.sagaState.claim(OrderFixture.DEFAULT_SAGA_ID)

            Then("잠금 아래에서 읽은 결제 성공 여부를 함께 돌려준다") {
                claimed?.paymentDone shouldBe true
            }
        }
    }

    Given("완료로 닫지 못한 RUNNING 사가") {
        val saga = OrderFixture.saga()
        val f = SagaStateFixture().withSaga(saga)

        When("전진 복구 실패를 기록하면") {
            val alert = f.sagaState.forwardRecoveryFailed(OrderFixture.DEFAULT_SAGA_ID, "lock wait timeout")

            Then("상태는 RUNNING 그대로이고 시도 횟수와 오류만 남는다") {
                saga.status shouldBe SagaStatus.RUNNING
                saga.attempts shouldBe 1
                saga.lastError shouldBe "lock wait timeout"
                alert?.attempts shouldBe 1
                alert?.lastError shouldBe "lock wait timeout"
            }
        }
    }

    Given("그사이 다른 주체가 이미 SUCCEEDED로 닫은 사가") {
        val saga = OrderFixture.saga()
        saga.transitionTo(SagaStatus.SUCCEEDED, OrderFixture.FIXED_TIME)
        val f = SagaStateFixture().withSaga(saga)

        When("전진 복구 실패를 기록하려 하면") {
            val alert = f.sagaState.forwardRecoveryFailed(OrderFixture.DEFAULT_SAGA_ID, "invalid transition")

            Then("실패가 아니므로 아무것도 기록하지 않고 null을 돌려준다") {
                alert.shouldBeNull()
                saga.attempts shouldBe 0
                saga.lastError.shouldBeNull()
            }
        }
    }
})
