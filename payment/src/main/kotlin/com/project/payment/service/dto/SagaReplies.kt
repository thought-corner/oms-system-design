package com.project.payment.service.dto

enum class PaymentMessageType(val direction: ReplyDirection) {
    PAYMENT_PAY(ReplyDirection.FORWARD),
    PAYMENT_CANCEL(ReplyDirection.CANCEL),
}

enum class ReplyDirection {
    FORWARD, CANCEL
}
