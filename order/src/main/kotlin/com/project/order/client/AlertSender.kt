package com.project.order.client

interface AlertSender {

    fun send(alert: CompensationFailedAlert)
}

data class CompensationFailedAlert(
    val sagaId: String,
    val orderId: Long,
    val attempts: Int,
    val lastError: String?,
    val stockDone: Boolean,
    val pointDone: Boolean,
    val paymentDone: Boolean,
) {

    val stuck: List<String>
        get() = buildList {
            if (stockDone) add("stock")
            if (pointDone) add("point")
            if (paymentDone) add("payment")
        }
}
