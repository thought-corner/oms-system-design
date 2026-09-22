package com.project.order.fixture

import com.project.order.domain.Order
import com.project.order.domain.OrderItem
import com.project.order.domain.OrderSaga
import com.project.order.domain.OrderStatus
import com.project.order.service.dto.SagaContext
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

object OrderFixture {

    const val DEFAULT_ORDER_ID = 10L
    const val DEFAULT_USER_ID = 1L
    const val DEFAULT_TOTAL_PRICE = 400L
    const val DEFAULT_SAGA_ID = "saga-1"

    val FIXED_INSTANT: Instant = Instant.parse("2026-09-22T03:00:00Z")
    val FIXED_CLOCK: Clock = Clock.fixed(FIXED_INSTANT, ZoneId.of("UTC"))
    val FIXED_TIME: LocalDateTime = LocalDateTime.of(2026, 9, 22, 3, 0)

    fun order(
        userId: Long = DEFAULT_USER_ID,
        status: OrderStatus = OrderStatus.CREATED,
        id: Long? = DEFAULT_ORDER_ID,
    ): Order = Order(userId = userId, createdAt = FIXED_TIME)
        .also { it.transitionTo(status, FIXED_TIME) }
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

    fun saga(
        sagaId: String = DEFAULT_SAGA_ID,
        orderId: Long = DEFAULT_ORDER_ID,
    ): OrderSaga = OrderSaga(sagaId = sagaId, orderId = orderId, createdAt = FIXED_TIME)

    fun context(
        sagaId: String = DEFAULT_SAGA_ID,
        orderId: Long = DEFAULT_ORDER_ID,
        userId: Long = DEFAULT_USER_ID,
        items: List<SagaContext.Item> = listOf(
            SagaContext.Item(productId = 1L, quantity = 2L),
            SagaContext.Item(productId = 2L, quantity = 1L),
        ),
    ): SagaContext = SagaContext(sagaId = sagaId, orderId = orderId, userId = userId, items = items)
}
