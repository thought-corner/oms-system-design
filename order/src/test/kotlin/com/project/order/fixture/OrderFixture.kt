package com.project.order.fixture

import com.project.order.domain.Order
import com.project.order.domain.OrderItem
import com.project.order.domain.OrderSaga
import com.project.order.domain.OrderStatus
import com.project.order.domain.OutboxMessage
import com.project.order.domain.OutboxStatus
import com.project.order.domain.SagaStatus
import com.project.order.domain.SagaStep
import org.springframework.test.util.ReflectionTestUtils
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

object OrderFixture {

    const val DEFAULT_ORDER_ID = 10L
    const val DEFAULT_USER_ID = 1L
    const val DEFAULT_TOTAL_PRICE = 400L
    const val DEFAULT_SAGA_ID = "saga-1"
    const val DEFAULT_IDEMPOTENCY_KEY = "key-1"

    val FIXED_INSTANT: Instant = Instant.parse("2026-09-22T03:00:00Z")
    val FIXED_CLOCK: Clock = Clock.fixed(FIXED_INSTANT, ZoneId.of("UTC"))
    val FIXED_TIME: LocalDateTime = LocalDateTime.of(2026, 9, 22, 3, 0)
    val STALE_TIME: LocalDateTime = FIXED_TIME.minusMinutes(2)

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
        idempotencyKey: String = "key-$sagaId",
        createdAt: LocalDateTime = FIXED_TIME,
    ): OrderSaga = OrderSaga(sagaId = sagaId, orderId = orderId, idempotencyKey = idempotencyKey, createdAt = createdAt)

    fun sagaAt(
        step: SagaStep,
        paymentDone: Boolean = false,
        attempts: Int = 0,
        updatedAt: LocalDateTime = STALE_TIME,
    ): OrderSaga = saga(createdAt = updatedAt).also { saga ->
        if (step != SagaStep.STOCK) saga.stockCompleted(DEFAULT_TOTAL_PRICE, updatedAt)
        if (step == SagaStep.PAYMENT) saga.pointCompleted(updatedAt)
        if (paymentDone) saga.paymentCompleted(updatedAt)
        repeat(attempts) { saga.countAttempt() }
    }

    fun compensatingSaga(
        status: SagaStatus = SagaStatus.COMPENSATING,
        canceled: List<SagaStep> = emptyList(),
        failureCode: String = "INSUFFICIENT_POINT",
        attempts: Int = 0,
        updatedAt: LocalDateTime = STALE_TIME,
    ): OrderSaga = sagaAt(SagaStep.POINT, updatedAt = updatedAt).also { saga ->
        saga.transitionTo(status, updatedAt)
        saga.recordFailure(failureCode)
        canceled.forEach { saga.canceled(it, updatedAt) }
        repeat(attempts) { saga.countAttempt() }
    }

    fun outbox(
        messageType: String = "STOCK_BUY",
        status: OutboxStatus = OutboxStatus.PENDING,
        occurredAt: LocalDateTime = FIXED_TIME,
        sagaId: String = DEFAULT_SAGA_ID,
    ): OutboxMessage = OutboxMessage(
        messageId = "message-$sagaId-$messageType",
        topic = "product.command",
        messageKey = DEFAULT_ORDER_ID.toString(),
        sagaId = sagaId,
        messageType = messageType,
        payload = ByteArray(0),
        occurredAt = occurredAt,
    ).also { ReflectionTestUtils.setField(it, "status", status) }
}
