package com.project.order.service

import com.project.common.exception.BusinessException
import com.project.order.domain.Order
import com.project.order.domain.OrderItem
import com.project.order.exception.OrderErrorCode
import com.project.order.repository.OrderItemRepository
import com.project.order.repository.OrderRepository
import com.project.order.repository.OrderSagaRepository
import com.project.order.service.dto.CreateOrderCommand
import com.project.order.service.dto.CreateOrderResult
import com.project.order.service.dto.OrderStatusResult
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDateTime

@Service
class OrderService(
    private val orderRepository: OrderRepository,
    private val orderItemRepository: OrderItemRepository,
    private val orderSagaRepository: OrderSagaRepository,
    private val clock: Clock,
) {

    @Transactional
    fun createOrder(command: CreateOrderCommand): CreateOrderResult {
        if (command.orderItems.isEmpty()) {
            throw BusinessException(OrderErrorCode.INVALID_ORDER, "orderItems is empty")
        }

        val order = orderRepository.save(Order(userId = command.userId, createdAt = LocalDateTime.now(clock)))
        val orderId = requireNotNull(order.id)

        orderItemRepository.saveAll(
            command.orderItems.map { OrderItem(orderId = orderId, productId = it.productId, quantity = it.quantity) },
        )

        return CreateOrderResult(orderId)
    }

    @Transactional(readOnly = true)
    fun findOrder(orderId: Long): OrderStatusResult {
        val order = orderRepository.findByIdOrNull(orderId)
            ?: throw BusinessException(OrderErrorCode.ORDER_NOT_FOUND, "orderId=$orderId")

        return OrderStatusResult.from(order, orderSagaRepository.findFirstByOrderIdOrderByIdDesc(orderId))
    }
}
