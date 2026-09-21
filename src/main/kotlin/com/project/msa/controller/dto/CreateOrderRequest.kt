package com.project.msa.controller.dto

import com.project.msa.service.dto.CreateOrderCommand

data class CreateOrderRequest(
    val userId: Long,
    val orderItems: List<OrderItem>,
) {

    fun toCreateOrderCommand(): CreateOrderCommand =
        CreateOrderCommand(
            userId = userId,
            orderItems = orderItems.map { CreateOrderCommand.OrderItem(it.productId, it.quantity) },
        )

    data class OrderItem(
        val productId: Long,
        val quantity: Long,
    )
}
