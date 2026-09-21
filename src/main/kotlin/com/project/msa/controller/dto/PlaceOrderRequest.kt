package com.project.msa.controller.dto

import com.project.msa.service.dto.PlaceOrderCommand

data class PlaceOrderRequest(
    val orderId: Long,
) {

    fun toPlaceOrderCommand(): PlaceOrderCommand = PlaceOrderCommand(orderId)
}
