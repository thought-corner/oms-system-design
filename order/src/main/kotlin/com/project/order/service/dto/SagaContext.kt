package com.project.order.service.dto

data class SagaContext(
    val sagaId: String,
    val orderId: Long,
    val userId: Long,
    val items: List<Item>,
) {

    data class Item(
        val productId: Long,
        val quantity: Long,
    )
}
