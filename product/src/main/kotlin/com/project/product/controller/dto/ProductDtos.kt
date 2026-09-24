package com.project.product.controller.dto

import com.project.product.service.dto.BuyCancelCommand
import com.project.product.service.dto.BuyCommand

data class BuyRequest(
    val sagaId: String,
    val orderId: Long,
    val items: List<Item>,
) {

    fun toCommand(): BuyCommand =
        BuyCommand(sagaId = sagaId, orderId = orderId, items = items.map { BuyCommand.Item(it.productId, it.quantity) })

    data class Item(
        val productId: Long,
        val quantity: Long,
    )
}

data class BuyResponse(val totalPrice: Long)

data class BuyCancelRequest(
    val sagaId: String,
    val orderId: Long,
) {

    fun toCommand(): BuyCancelCommand = BuyCancelCommand(sagaId = sagaId, orderId = orderId)
}

data class BuyCancelResponse(val restoredPrice: Long)
