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
        val service = OrderService(orderRepository, orderItemRepository, OrderFixture.FIXED_CLOCK)

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
        val service = OrderService(orderRepository, orderItemRepository, OrderFixture.FIXED_CLOCK)
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
        val service = OrderService(orderRepository, orderItemRepository, OrderFixture.FIXED_CLOCK)
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
})
