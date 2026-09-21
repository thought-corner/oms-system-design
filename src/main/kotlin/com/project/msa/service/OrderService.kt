package com.project.msa.service

import com.project.msa.domain.Order
import com.project.msa.domain.OrderEvent
import com.project.msa.domain.OrderItem
import com.project.msa.exception.BusinessException
import com.project.msa.exception.OrderErrorCode
import com.project.msa.repository.OrderItemRepository
import com.project.msa.repository.OrderRepository
import com.project.msa.service.dto.CreateOrderCommand
import com.project.msa.service.dto.CreateOrderResult
import com.project.msa.service.dto.PlaceOrderCommand
import com.project.msa.statemachine.OrderStateMachine
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class OrderService(
    private val orderRepository: OrderRepository,
    private val orderItemRepository: OrderItemRepository,
    private val productService: ProductService,
    private val paymentService: PaymentService,
    private val orderStateMachine: OrderStateMachine,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun createOrder(command: CreateOrderCommand): CreateOrderResult {
        if (command.orderItems.isEmpty()) {
            throw BusinessException(OrderErrorCode.INVALID_ORDER, "orderItems is empty")
        }

        val order = orderRepository.save(Order(userId = command.userId))
        val orderId = requireNotNull(order.id)

        orderItemRepository.saveAll(
            command.orderItems.map { OrderItem(orderId = orderId, productId = it.productId, quantity = it.quantity) },
        )

        return CreateOrderResult(orderId)
    }

    @Transactional
    fun placeOrder(command: PlaceOrderCommand) {
        val order = orderRepository.findWithLockById(command.orderId)
            ?: throw BusinessException(OrderErrorCode.ORDER_NOT_FOUND, "orderId=${command.orderId}")

        if (order.isCompleted) {
            return
        }

        val previous = order.status
        val next = orderStateMachine.transition(command.orderId, previous, OrderEvent.PLACE)

        val totalPrice = orderItemRepository.findAllByOrderId(command.orderId)
            .sortedBy { it.productId }
            .sumOf { item -> productService.buy(item.productId, item.quantity) }

        paymentService.pay(command.orderId, order.userId, totalPrice)

        order.transitionTo(next)
        log.info("Order state transition applied: orderId={}, {} -> {}", command.orderId, previous, next)
    }
}
