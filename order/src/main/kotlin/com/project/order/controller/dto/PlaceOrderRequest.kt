package com.project.order.controller.dto

import com.project.order.service.dto.PlaceOrderCommand

data class PlaceOrderRequest(
    val orderId: Long,
) {

    fun toPlaceOrderCommand(idempotencyKey: String?): PlaceOrderCommand = PlaceOrderCommand(orderId, idempotencyKey)
}
