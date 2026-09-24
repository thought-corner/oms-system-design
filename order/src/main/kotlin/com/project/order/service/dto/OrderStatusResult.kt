package com.project.order.service.dto

import com.project.order.domain.Order
import com.project.order.domain.OrderSaga

data class OrderStatusResult(
    val orderId: Long,
    val status: String,
    val code: String?,
    val placing: Boolean,
) {

    companion object {
        fun from(order: Order, latestSaga: OrderSaga?): OrderStatusResult =
            OrderStatusResult(
                orderId = requireNotNull(order.id),
                status = order.status.name,
                code = latestSaga?.failureCode,
                placing = order.isPlacing,
            )
    }
}
