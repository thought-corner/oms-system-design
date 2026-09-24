package com.project.order.service

import com.project.order.domain.Order
import com.project.order.domain.OrderItem
import com.project.common.exception.BusinessException
import com.project.order.exception.OrderErrorCode
import com.project.order.fixture.CommandFixture
import com.project.order.fixture.OrderFixture
import com.project.order.fixture.withId
import com.project.order.repository.OrderItemRepository
import com.project.order.repository.OrderRepository
import com.project.order.repository.OrderSagaRepository
import com.project.order.domain.OrderStatus
import com.project.order.domain.SagaStep
import io.kotest.matchers.nulls.shouldBeNull
import java.util.Optional
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify

class OrderServiceTest : BehaviorSpec({

    Given("항목이 비어 있는 주문 생성 요청") {
        val orderRepository = mockk<OrderRepository>()
        val orderItemRepository = mockk<OrderItemRepository>()
        val service = OrderService(orderRepository, orderItemRepository, mockk<OrderSagaRepository>(), OrderFixture.FIXED_CLOCK)

        When("주문을 만들면") {
            val exception = shouldThrow<BusinessException> {
                service.createOrder(CommandFixture.createOrderCommand(orderItems = emptyList()))
            }

            Then("INVALID_ORDER이고 아무것도 저장하지 않는다") {
                exception.errorCode shouldBe OrderErrorCode.INVALID_ORDER
                exception.message shouldContain "orderItems is empty"
                verify(exactly = 0) { orderRepository.save(any()) }
                verify(exactly = 0) { orderItemRepository.saveAll(any<List<OrderItem>>()) }
            }
        }
    }

    Given("주문 수량이 0인 항목이 섞인 요청") {
        val orderRepository = mockk<OrderRepository>()
        val orderItemRepository = mockk<OrderItemRepository>()
        val service = OrderService(orderRepository, orderItemRepository, mockk<OrderSagaRepository>(), OrderFixture.FIXED_CLOCK)
        every { orderRepository.save(any()) } answers { firstArg<Order>().withId(OrderFixture.DEFAULT_ORDER_ID) }

        When("주문을 만들면") {
            val exception = shouldThrow<BusinessException> {
                service.createOrder(
                    CommandFixture.createOrderCommand(
                        orderItems = listOf(
                            com.project.order.service.dto.CreateOrderCommand.OrderItem(productId = 1L, quantity = 0L),
                        ),
                    ),
                )
            }

            Then("INVALID_ORDER이고 항목을 저장하지 않는다") {
                exception.errorCode shouldBe OrderErrorCode.INVALID_ORDER
                verify(exactly = 0) { orderItemRepository.saveAll(any<List<OrderItem>>()) }
            }
        }
    }

    Given("항목 두 개짜리 주문 생성 요청") {
        val orderRepository = mockk<OrderRepository>()
        val orderItemRepository = mockk<OrderItemRepository>()
        val service = OrderService(orderRepository, orderItemRepository, mockk<OrderSagaRepository>(), OrderFixture.FIXED_CLOCK)
        val saved = slot<List<OrderItem>>()
        every { orderRepository.save(any()) } answers { firstArg<Order>().withId(OrderFixture.DEFAULT_ORDER_ID) }
        every { orderItemRepository.saveAll(capture(saved)) } answers { saved.captured }

        When("주문을 만들면") {
            val result = service.createOrder(CommandFixture.createOrderCommand())

            Then("orderId를 돌려주고 항목을 그 주문에 매단다") {
                result.orderId shouldBe OrderFixture.DEFAULT_ORDER_ID
                saved.captured.size shouldBe 2
                saved.captured.map { it.orderId }.toSet() shouldBe setOf(OrderFixture.DEFAULT_ORDER_ID)
            }

            Then("재고와 포인트는 건드리지 않는다") {
                verify(exactly = 1) { orderRepository.save(any()) }
                verify(exactly = 1) { orderItemRepository.saveAll(any<List<OrderItem>>()) }
            }
        }
    }

    Given("없는 주문 999") {
        val orderRepository = mockk<OrderRepository>()
        val sagaRepository = mockk<OrderSagaRepository>()
        val service = OrderService(orderRepository, mockk(), sagaRepository, OrderFixture.FIXED_CLOCK)
        every { orderRepository.findById(999L) } returns Optional.empty()

        When("조회하면") {
            val exception = shouldThrow<BusinessException> { service.findOrder(999L) }

            Then("AC-7 ORDER_NOT_FOUND") {
                exception.errorCode shouldBe OrderErrorCode.ORDER_NOT_FOUND
                verify(exactly = 0) { sagaRepository.findFirstByOrderIdOrderByIdDesc(any()) }
            }
        }
    }

    Given("결제를 한 번도 시도하지 않은 주문") {
        val orderRepository = mockk<OrderRepository>()
        val sagaRepository = mockk<OrderSagaRepository>()
        val service = OrderService(orderRepository, mockk(), sagaRepository, OrderFixture.FIXED_CLOCK)
        every { orderRepository.findById(OrderFixture.DEFAULT_ORDER_ID) } returns Optional.of(OrderFixture.order())
        every { sagaRepository.findFirstByOrderIdOrderByIdDesc(OrderFixture.DEFAULT_ORDER_ID) } returns null

        When("조회하면") {
            val result = service.findOrder(OrderFixture.DEFAULT_ORDER_ID)

            Then("CREATED 이고 code 가 없다") {
                result.status shouldBe "CREATED"
                result.code.shouldBeNull()
                result.placing shouldBe false
            }
        }
    }

    Given("잔액 부족으로 보상 중인 주문") {
        val orderRepository = mockk<OrderRepository>()
        val sagaRepository = mockk<OrderSagaRepository>()
        val service = OrderService(orderRepository, mockk(), sagaRepository, OrderFixture.FIXED_CLOCK)
        every { orderRepository.findById(OrderFixture.DEFAULT_ORDER_ID) } returns Optional.of(OrderFixture.order(status = OrderStatus.PLACING))
        every { sagaRepository.findFirstByOrderIdOrderByIdDesc(OrderFixture.DEFAULT_ORDER_ID) } returns
            OrderFixture.compensatingSaga(failureCode = "INSUFFICIENT_POINT")

        When("조회하면") {
            val result = service.findOrder(OrderFixture.DEFAULT_ORDER_ID)

            Then("B-3 PLACING 과 최신 사가의 실패 code 를 함께 준다") {
                result.orderId shouldBe OrderFixture.DEFAULT_ORDER_ID
                result.status shouldBe "PLACING"
                result.code shouldBe "INSUFFICIENT_POINT"
                result.placing shouldBe true
            }
        }
    }

    Given("재결제로 새 사가가 진행 중인 주문") {
        val orderRepository = mockk<OrderRepository>()
        val sagaRepository = mockk<OrderSagaRepository>()
        val service = OrderService(orderRepository, mockk(), sagaRepository, OrderFixture.FIXED_CLOCK)
        every { orderRepository.findById(OrderFixture.DEFAULT_ORDER_ID) } returns Optional.of(OrderFixture.order(status = OrderStatus.PLACING))
        every { sagaRepository.findFirstByOrderIdOrderByIdDesc(OrderFixture.DEFAULT_ORDER_ID) } returns OrderFixture.sagaAt(SagaStep.POINT)

        When("조회하면") {
            val result = service.findOrder(OrderFixture.DEFAULT_ORDER_ID)

            Then("B-14 옛 사가의 사유가 새 사가를 덮지 않는다") {
                result.code.shouldBeNull()
            }
        }
    }
})
