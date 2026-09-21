package com.project.msa.service

import com.project.msa.domain.Order
import com.project.msa.domain.OrderEvent
import com.project.msa.domain.OrderItem
import com.project.msa.domain.OrderStatus
import com.project.msa.exception.BusinessException
import com.project.msa.exception.OrderErrorCode
import com.project.msa.exception.PointErrorCode
import com.project.msa.exception.ProductErrorCode
import com.project.msa.fixture.CommandFixture
import com.project.msa.fixture.OrderFixture
import com.project.msa.fixture.PaymentFixture
import com.project.msa.fixture.withId
import com.project.msa.repository.OrderItemRepository
import com.project.msa.repository.OrderRepository
import com.project.msa.service.dto.CreateOrderCommand
import com.project.msa.statemachine.OrderStateMachine
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.mockk.verifyOrder
import org.springframework.dao.CannotAcquireLockException

private fun stubPlaceableOrder(
    orderRepository: OrderRepository,
    orderItemRepository: OrderItemRepository,
    orderStateMachine: OrderStateMachine,
    order: Order = OrderFixture.order(),
    items: List<OrderItem> = OrderFixture.itemsInReverseProductOrder(),
): Order {
    every { orderRepository.findWithLockById(OrderFixture.DEFAULT_ORDER_ID) } returns order
    every { orderItemRepository.findAllByOrderId(OrderFixture.DEFAULT_ORDER_ID) } returns items
    every {
        orderStateMachine.transition(OrderFixture.DEFAULT_ORDER_ID, OrderStatus.CREATED, OrderEvent.PLACE)
    } returns OrderStatus.COMPLETED
    return order
}

private fun stubProductsInStock(productService: ProductService) {
    every { productService.buy(1L, 2L) } returns 200L
    every { productService.buy(2L, 1L) } returns 200L
}

class OrderServiceTest : BehaviorSpec({

    Given("존재하지 않는 주문 999") {
        val orderRepository = mockk<OrderRepository>()
        val orderItemRepository = mockk<OrderItemRepository>()
        val productService = mockk<ProductService>()
        val paymentService = mockk<PaymentService>()
        val orderStateMachine = mockk<OrderStateMachine>()
        val service = OrderService(orderRepository, orderItemRepository, productService, paymentService, orderStateMachine)
        every { orderRepository.findWithLockById(999L) } returns null

        When("결제하면") {
            val exception = shouldThrow<BusinessException> { service.placeOrder(CommandFixture.placeOrderCommand(999L)) }

            Then("AC-7 ORDER_NOT_FOUND 이고 재고·결제를 부르지 않는다") {
                exception.errorCode shouldBe OrderErrorCode.ORDER_NOT_FOUND
                exception.message shouldContain "orderId=999"
                verify { productService wasNot Called }
                verify { paymentService wasNot Called }
            }
        }
    }

    Given("다른 트랜잭션이 행 락을 잡고 있는 주문 10") {
        val orderRepository = mockk<OrderRepository>()
        val orderItemRepository = mockk<OrderItemRepository>()
        val productService = mockk<ProductService>()
        val paymentService = mockk<PaymentService>()
        val orderStateMachine = mockk<OrderStateMachine>()
        val service = OrderService(orderRepository, orderItemRepository, productService, paymentService, orderStateMachine)
        every { orderRepository.findWithLockById(OrderFixture.DEFAULT_ORDER_ID) } throws CannotAcquireLockException("NOWAIT")

        When("결제하면") {
            shouldThrow<CannotAcquireLockException> { service.placeOrder(CommandFixture.placeOrderCommand()) }

            Then("AC-6 락 예외가 그대로 관통하고 아무것도 부르지 않는다") {
                verify { orderStateMachine wasNot Called }
                verify { productService wasNot Called }
                verify { paymentService wasNot Called }
            }
        }
    }

    Given("상태 기계가 PLACE 전이를 거부하는 주문 10") {
        val orderRepository = mockk<OrderRepository>()
        val orderItemRepository = mockk<OrderItemRepository>()
        val productService = mockk<ProductService>()
        val paymentService = mockk<PaymentService>()
        val orderStateMachine = mockk<OrderStateMachine>()
        val service = OrderService(orderRepository, orderItemRepository, productService, paymentService, orderStateMachine)
        val order = stubPlaceableOrder(orderRepository, orderItemRepository, orderStateMachine)
        every {
            orderStateMachine.transition(OrderFixture.DEFAULT_ORDER_ID, OrderStatus.CREATED, OrderEvent.PLACE)
        } throws BusinessException(OrderErrorCode.INVALID_ORDER_STATE_TRANSITION)

        When("결제하면") {
            val exception = shouldThrow<BusinessException> { service.placeOrder(CommandFixture.placeOrderCommand()) }

            Then("INVALID_ORDER_STATE_TRANSITION 이고 재고·결제를 부르지 않으며 주문은 CREATED") {
                exception.errorCode shouldBe OrderErrorCode.INVALID_ORDER_STATE_TRANSITION
                verify { productService wasNot Called }
                verify { paymentService wasNot Called }
                order.status shouldBe OrderStatus.CREATED
            }
        }
    }

    Given("상품2 재고가 부족한 주문 10") {
        val orderRepository = mockk<OrderRepository>()
        val orderItemRepository = mockk<OrderItemRepository>()
        val productService = mockk<ProductService>()
        val paymentService = mockk<PaymentService>()
        val orderStateMachine = mockk<OrderStateMachine>()
        val service = OrderService(orderRepository, orderItemRepository, productService, paymentService, orderStateMachine)
        val order = stubPlaceableOrder(orderRepository, orderItemRepository, orderStateMachine)
        every { productService.buy(1L, 2L) } returns 200L
        every { productService.buy(2L, 1L) } throws BusinessException(ProductErrorCode.INSUFFICIENT_STOCK)

        When("결제하면") {
            val exception = shouldThrow<BusinessException> { service.placeOrder(CommandFixture.placeOrderCommand()) }

            Then("AC-3 INSUFFICIENT_STOCK 이고 결제하지 않으며 주문은 CREATED") {
                exception.errorCode shouldBe ProductErrorCode.INSUFFICIENT_STOCK
                verify { paymentService wasNot Called }
                order.status shouldBe OrderStatus.CREATED
            }
        }
    }

    Given("잔액이 총액보다 적은 사용자의 주문 10") {
        val orderRepository = mockk<OrderRepository>()
        val orderItemRepository = mockk<OrderItemRepository>()
        val productService = mockk<ProductService>()
        val paymentService = mockk<PaymentService>()
        val orderStateMachine = mockk<OrderStateMachine>()
        val service = OrderService(orderRepository, orderItemRepository, productService, paymentService, orderStateMachine)
        val order = stubPlaceableOrder(orderRepository, orderItemRepository, orderStateMachine)
        stubProductsInStock(productService)
        every {
            paymentService.pay(OrderFixture.DEFAULT_ORDER_ID, OrderFixture.DEFAULT_USER_ID, OrderFixture.DEFAULT_TOTAL_PRICE)
        } throws BusinessException(PointErrorCode.INSUFFICIENT_POINT)

        When("결제하면") {
            val exception = shouldThrow<BusinessException> { service.placeOrder(CommandFixture.placeOrderCommand()) }

            Then("AC-4 INSUFFICIENT_POINT 이고 주문은 CREATED") {
                exception.errorCode shouldBe PointErrorCode.INSUFFICIENT_POINT
                order.status shouldBe OrderStatus.CREATED
            }
        }
    }

    Given("이미 COMPLETED 인 주문 10") {
        val orderRepository = mockk<OrderRepository>()
        val orderItemRepository = mockk<OrderItemRepository>()
        val productService = mockk<ProductService>()
        val paymentService = mockk<PaymentService>()
        val orderStateMachine = mockk<OrderStateMachine>()
        val service = OrderService(orderRepository, orderItemRepository, productService, paymentService, orderStateMachine)
        val order = OrderFixture.order(status = OrderStatus.COMPLETED)
        every { orderRepository.findWithLockById(OrderFixture.DEFAULT_ORDER_ID) } returns order

        When("같은 주문을 다시 결제하면") {
            service.placeOrder(CommandFixture.placeOrderCommand())

            Then("AC-5 예외 없이 끝나고 전이·차감·결제를 부르지 않는다") {
                order.status shouldBe OrderStatus.COMPLETED
                verify { orderStateMachine wasNot Called }
                verify { orderItemRepository wasNot Called }
                verify { productService wasNot Called }
                verify { paymentService wasNot Called }
            }
        }
    }

    Given("항목이 없는 주문 생성 명령") {
        val orderRepository = mockk<OrderRepository>()
        val orderItemRepository = mockk<OrderItemRepository>()
        val productService = mockk<ProductService>()
        val paymentService = mockk<PaymentService>()
        val orderStateMachine = mockk<OrderStateMachine>()
        val service = OrderService(orderRepository, orderItemRepository, productService, paymentService, orderStateMachine)

        When("주문을 만들면") {
            val exception = shouldThrow<BusinessException> {
                service.createOrder(CommandFixture.createOrderCommand(orderItems = emptyList()))
            }

            Then("AC-10 INVALID_ORDER 이고 아무것도 저장하지 않는다") {
                exception.errorCode shouldBe OrderErrorCode.INVALID_ORDER
                verify { orderRepository wasNot Called }
                verify { orderItemRepository wasNot Called }
            }
        }
    }

    Given("주문 수량이 0 인 항목을 가진 주문 생성 명령") {
        val orderRepository = mockk<OrderRepository>()
        val orderItemRepository = mockk<OrderItemRepository>()
        val productService = mockk<ProductService>()
        val paymentService = mockk<PaymentService>()
        val orderStateMachine = mockk<OrderStateMachine>()
        val service = OrderService(orderRepository, orderItemRepository, productService, paymentService, orderStateMachine)
        every { orderRepository.save(any()) } answers { firstArg<Order>().withId(5L) }
        val command = CommandFixture.createOrderCommand(orderItems = listOf(CreateOrderCommand.OrderItem(productId = 1L, quantity = 0L)))

        When("주문을 만들면") {
            val exception = shouldThrow<BusinessException> { service.createOrder(command) }

            Then("AC-10 INVALID_ORDER 이고 항목은 저장되지 않는다 — 주문 행은 트랜잭션 롤백이 지운다") {
                exception.errorCode shouldBe OrderErrorCode.INVALID_ORDER
                verify(exactly = 0) { orderItemRepository.saveAll(any<List<OrderItem>>()) }
                verify { productService wasNot Called }
                verify { paymentService wasNot Called }
            }
        }
    }

    Given("상품1×2·상품2×1 인 사용자 1 의 CREATED 주문 10") {
        val orderRepository = mockk<OrderRepository>()
        val orderItemRepository = mockk<OrderItemRepository>()
        val productService = mockk<ProductService>()
        val paymentService = mockk<PaymentService>()
        val orderStateMachine = mockk<OrderStateMachine>()
        val service = OrderService(orderRepository, orderItemRepository, productService, paymentService, orderStateMachine)
        val order = stubPlaceableOrder(orderRepository, orderItemRepository, orderStateMachine)
        stubProductsInStock(productService)
        every {
            paymentService.pay(OrderFixture.DEFAULT_ORDER_ID, OrderFixture.DEFAULT_USER_ID, OrderFixture.DEFAULT_TOTAL_PRICE)
        } returns PaymentFixture.payment()

        When("결제하면") {
            service.placeOrder(CommandFixture.placeOrderCommand())

            Then("AC-2 총액 400 을 결제하고 주문은 COMPLETED") {
                verify(exactly = 1) {
                    paymentService.pay(OrderFixture.DEFAULT_ORDER_ID, OrderFixture.DEFAULT_USER_ID, OrderFixture.DEFAULT_TOTAL_PRICE)
                }
                order.status shouldBe OrderStatus.COMPLETED
            }
        }
    }

    Given("항목이 productId 역순(2, 1)으로 저장된 주문 10") {
        val orderRepository = mockk<OrderRepository>()
        val orderItemRepository = mockk<OrderItemRepository>()
        val productService = mockk<ProductService>()
        val paymentService = mockk<PaymentService>()
        val orderStateMachine = mockk<OrderStateMachine>()
        val service = OrderService(orderRepository, orderItemRepository, productService, paymentService, orderStateMachine)
        stubPlaceableOrder(orderRepository, orderItemRepository, orderStateMachine, items = OrderFixture.itemsInReverseProductOrder())
        stubProductsInStock(productService)
        every {
            paymentService.pay(OrderFixture.DEFAULT_ORDER_ID, OrderFixture.DEFAULT_USER_ID, OrderFixture.DEFAULT_TOTAL_PRICE)
        } returns PaymentFixture.payment()

        When("결제하면") {
            service.placeOrder(CommandFixture.placeOrderCommand())

            Then("주문 락 → 전이 → 상품 productId 오름차순 차감 → 결제 순으로 부른다") {
                verifyOrder {
                    orderRepository.findWithLockById(OrderFixture.DEFAULT_ORDER_ID)
                    orderStateMachine.transition(OrderFixture.DEFAULT_ORDER_ID, OrderStatus.CREATED, OrderEvent.PLACE)
                    productService.buy(1L, 2L)
                    productService.buy(2L, 1L)
                    paymentService.pay(OrderFixture.DEFAULT_ORDER_ID, OrderFixture.DEFAULT_USER_ID, OrderFixture.DEFAULT_TOTAL_PRICE)
                }
            }
        }
    }

    Given("사용자 2 의 CREATED 주문 10") {
        val orderRepository = mockk<OrderRepository>()
        val orderItemRepository = mockk<OrderItemRepository>()
        val productService = mockk<ProductService>()
        val paymentService = mockk<PaymentService>()
        val orderStateMachine = mockk<OrderStateMachine>()
        val service = OrderService(orderRepository, orderItemRepository, productService, paymentService, orderStateMachine)
        stubPlaceableOrder(orderRepository, orderItemRepository, orderStateMachine, order = OrderFixture.order(userId = 2L))
        stubProductsInStock(productService)
        every { paymentService.pay(OrderFixture.DEFAULT_ORDER_ID, 2L, OrderFixture.DEFAULT_TOTAL_PRICE) } returns PaymentFixture.payment(userId = 2L)

        When("결제하면") {
            service.placeOrder(CommandFixture.placeOrderCommand())

            Then("AC-8 주문의 사용자 2 로 결제한다") {
                verify(exactly = 1) { paymentService.pay(OrderFixture.DEFAULT_ORDER_ID, 2L, OrderFixture.DEFAULT_TOTAL_PRICE) }
            }
        }
    }

    Given("사용자 2 · 상품1×2·상품2×1 인 주문 생성 명령") {
        val orderRepository = mockk<OrderRepository>()
        val orderItemRepository = mockk<OrderItemRepository>()
        val productService = mockk<ProductService>()
        val paymentService = mockk<PaymentService>()
        val orderStateMachine = mockk<OrderStateMachine>()
        val service = OrderService(orderRepository, orderItemRepository, productService, paymentService, orderStateMachine)
        val savedOrder = slot<Order>()
        val savedItems = slot<List<OrderItem>>()
        every { orderRepository.save(capture(savedOrder)) } answers { firstArg<Order>().withId(5L) }
        every { orderItemRepository.saveAll(capture(savedItems)) } answers { firstArg<List<OrderItem>>() }

        When("주문을 만들면") {
            val result = service.createOrder(CommandFixture.createOrderCommand(userId = 2L))

            Then("AC-1 발급된 orderId 5 를 돌려주고 주문·항목만 저장하며 재고·결제는 건드리지 않는다") {
                result.orderId shouldBe 5L
                savedOrder.captured.userId shouldBe 2L
                savedOrder.captured.status shouldBe OrderStatus.CREATED
                savedItems.captured.map { Triple(it.orderId, it.productId, it.quantity) } shouldContainExactly listOf(
                    Triple(5L, 1L, 2L),
                    Triple(5L, 2L, 1L),
                )
                verify { productService wasNot Called }
                verify { paymentService wasNot Called }
            }
        }
    }
})
