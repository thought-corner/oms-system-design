package com.project.payment.service.dto

import java.time.LocalDateTime

data class PayCommand(
    val sagaId: String,
    val orderId: Long,
    val userId: Long,
    val amount: Long,
)

data class PayResult(
    val paymentId: Long,
    val paidAt: LocalDateTime,
)

data class PayCancelCommand(
    val sagaId: String,
    val orderId: Long,
)
