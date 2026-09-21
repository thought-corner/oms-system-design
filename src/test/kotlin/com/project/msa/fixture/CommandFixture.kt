package com.project.msa.fixture

import com.project.msa.service.dto.CreateOrderCommand
import com.project.msa.service.dto.PlaceOrderCommand

object CommandFixture {

    fun createOrderCommand(
        userId: Long = OrderFixture.DEFAULT_USER_ID,
        orderItems: List<CreateOrderCommand.OrderItem> = listOf(
            CreateOrderCommand.OrderItem(productId = 1L, quantity = 2L),
            CreateOrderCommand.OrderItem(productId = 2L, quantity = 1L),
        ),
    ): CreateOrderCommand = CreateOrderCommand(userId = userId, orderItems = orderItems)

    fun placeOrderCommand(orderId: Long = OrderFixture.DEFAULT_ORDER_ID): PlaceOrderCommand = PlaceOrderCommand(orderId)
}
