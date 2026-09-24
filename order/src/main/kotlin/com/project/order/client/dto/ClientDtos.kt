package com.project.order.client.dto

import java.time.LocalDateTime

data class BuyApiRequest(
    val sagaId: String,
    val orderId: Long,
    val items: List<Item>,
) {

    data class Item(
        val productId: Long,
        val quantity: Long,
    )
}

data class BuyApiResponse(val totalPrice: Long)

data class BuyCancelApiRequest(
    val sagaId: String,
    val orderId: Long,
)

data class BuyCancelApiResponse(val restoredPrice: Long)

data class UseApiRequest(
    val sagaId: String,
    val orderId: Long,
    val userId: Long,
    val amount: Long,
)

data class UseCancelApiRequest(
    val sagaId: String,
    val orderId: Long,
)

data class UseCancelApiResponse(val refundedAmount: Long)

data class PayApiRequest(
    val sagaId: String,
    val orderId: Long,
    val userId: Long,
    val amount: Long,
)

data class PayApiResponse(
    val paymentId: Long,
    val paidAt: LocalDateTime,
)

data class PayCancelApiRequest(
    val sagaId: String,
    val orderId: Long,
)

data class RemoteErrorBody(
    val code: String? = null,
    val message: String? = null,
)
