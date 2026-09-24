package com.project.order.service.dto

data class CreateOrderCommand(
    val userId: Long,
    val orderItems: List<OrderItem>,
) {

    data class OrderItem(
        val productId: Long,
        val quantity: Long,
    )
}
