package com.project.product.service.dto

data class BuyCommand(
    val sagaId: String,
    val orderId: Long,
    val items: List<Item>,
) {

    data class Item(
        val productId: Long,
        val quantity: Long,
    )
}

data class BuyCancelCommand(
    val sagaId: String,
    val orderId: Long,
)
