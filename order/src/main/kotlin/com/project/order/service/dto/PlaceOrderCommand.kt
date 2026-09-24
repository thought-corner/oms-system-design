package com.project.order.service.dto

data class PlaceOrderCommand(
    val orderId: Long,
    val idempotencyKey: String?,
)
