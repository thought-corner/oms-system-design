package com.project.product.messaging.dto

import com.project.message.product.StockBuyCommand
import com.project.message.product.StockCancelCommand
import com.project.product.service.dto.BuyCancelCommand
import com.project.product.service.dto.BuyCommand

fun StockBuyCommand.toCommand(): BuyCommand {
    requireSagaTarget(sagaId, orderId)
    require(itemsCount > 0) { "items missing: sagaId=$sagaId" }
    require(itemsList.all { it.productId > 0 && it.quantity > 0 }) { "item missing productId or quantity: sagaId=$sagaId" }
    return BuyCommand(sagaId = sagaId, orderId = orderId, items = itemsList.map { BuyCommand.Item(it.productId, it.quantity) })
}

fun StockCancelCommand.toCommand(): BuyCancelCommand {
    requireSagaTarget(sagaId, orderId)
    return BuyCancelCommand(sagaId = sagaId, orderId = orderId)
}

private fun requireSagaTarget(sagaId: String, orderId: Long) {
    require(sagaId.isNotBlank()) { "sagaId missing" }
    require(orderId > 0) { "orderId missing: sagaId=$sagaId" }
}
