package com.project.payment.service.dto

enum class PaymentMessageType(val direction: ReplyDirection) {
    PAYMENT_PAY(ReplyDirection.FORWARD),
    PAYMENT_CANCEL(ReplyDirection.CANCEL),
}

enum class ReplyDirection {
    FORWARD, CANCEL
}

enum class ReplyOutcome {
    SUCCEEDED, FAILED
}

data class SagaReply(
    val sagaId: String,
    val orderId: Long,
    val step: String,
    val direction: ReplyDirection,
    val outcome: ReplyOutcome,
    val code: String?,
    val result: Any,
)

data class DeadLetter(
    val topic: String,
    val orderId: String?,
    val sagaId: String?,
    val messageType: String?,
    val exceptionClass: String?,
    val exceptionMessage: String?,
)
