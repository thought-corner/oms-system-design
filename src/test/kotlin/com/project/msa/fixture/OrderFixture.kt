package com.project.msa.fixture

import com.project.msa.domain.Order
import com.project.msa.domain.OrderItem
import com.project.msa.domain.OrderStatus

object OrderFixture {

    const val DEFAULT_ORDER_ID = 10L
    const val DEFAULT_USER_ID = 1L

    fun order(
        userId: Long = DEFAULT_USER_ID,
        status: OrderStatus = OrderStatus.CREATED,
        id: Long? = DEFAULT_ORDER_ID,
    ): Order = Order(userId = userId)
        .also { it.transitionTo(status) }
        .let { if (id == null) it else it.withId(id) }

    fun orderItem(
        orderId: Long = DEFAULT_ORDER_ID,
        productId: Long = 1L,
        quantity: Long = 2L,
    ): OrderItem = OrderItem(orderId = orderId, productId = productId, quantity = quantity)

    fun itemsInReverseProductOrder(orderId: Long = DEFAULT_ORDER_ID): List<OrderItem> = listOf(
        orderItem(orderId = orderId, productId = 2L, quantity = 1L),
        orderItem(orderId = orderId, productId = 1L, quantity = 2L),
    )

    const val DEFAULT_TOTAL_PRICE = 400L
}
