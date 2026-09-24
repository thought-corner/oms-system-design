package com.project.product.messaging.dto

import com.project.product.service.dto.BuyCancelCommand
import com.project.product.service.dto.BuyCommand

data class StockBuyMessage(
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

data class StockCancelMessage(
    val sagaId: String,
    val orderId: Long,
) {

    fun toCommand(): BuyCancelCommand = BuyCancelCommand(sagaId = sagaId, orderId = orderId)
}
