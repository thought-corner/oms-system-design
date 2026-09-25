package com.project.order.fixture

import com.project.order.domain.SagaStep
import com.project.order.service.dto.CreateOrderCommand
import com.project.order.service.dto.PlaceOrderCommand
import com.project.order.service.dto.ReplyDirection
import com.project.order.service.dto.ReplyOutcome
import com.project.order.service.dto.SagaReplyCommand

object CommandFixture {

    fun createOrderCommand(
        userId: Long = OrderFixture.DEFAULT_USER_ID,
        orderItems: List<CreateOrderCommand.OrderItem> = listOf(
            CreateOrderCommand.OrderItem(productId = 1L, quantity = 2L),
            CreateOrderCommand.OrderItem(productId = 2L, quantity = 1L),
        ),
    ): CreateOrderCommand = CreateOrderCommand(userId = userId, orderItems = orderItems)

    fun placeOrderCommand(
        orderId: Long = OrderFixture.DEFAULT_ORDER_ID,
        idempotencyKey: String? = OrderFixture.DEFAULT_IDEMPOTENCY_KEY,
    ): PlaceOrderCommand = PlaceOrderCommand(orderId, idempotencyKey)

    fun succeeded(step: SagaStep, totalPrice: Long? = null, sagaId: String = OrderFixture.DEFAULT_SAGA_ID): SagaReplyCommand =
        reply(step, ReplyDirection.FORWARD, ReplyOutcome.SUCCEEDED, code = null, totalPrice = totalPrice, sagaId = sagaId)

    fun failed(step: SagaStep, code: String?, sagaId: String = OrderFixture.DEFAULT_SAGA_ID): SagaReplyCommand =
        reply(step, ReplyDirection.FORWARD, ReplyOutcome.FAILED, code = code, totalPrice = null, sagaId = sagaId)

    fun canceled(step: SagaStep, outcome: ReplyOutcome = ReplyOutcome.SUCCEEDED): SagaReplyCommand =
        reply(step, ReplyDirection.CANCEL, outcome, code = null, totalPrice = null)

    private fun reply(
        step: SagaStep,
        direction: ReplyDirection,
        outcome: ReplyOutcome,
        code: String?,
        totalPrice: Long?,
        sagaId: String = OrderFixture.DEFAULT_SAGA_ID,
    ): SagaReplyCommand = SagaReplyCommand(
        sagaId = sagaId,
        orderId = OrderFixture.DEFAULT_ORDER_ID,
        step = step,
        direction = direction,
        outcome = outcome,
        code = code,
        totalPrice = totalPrice,
        messageType = null,
    )
}
