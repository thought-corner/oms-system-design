package com.project.payment.service.dto

data class PayCommand(
    val sagaId: String,
    val orderId: Long,
    val userId: Long,
    val amount: Long,
)

data class PayCancelCommand(
    val sagaId: String,
    val orderId: Long,
)

data class DeadLetterCommand(
    val topic: String,
    val orderId: String?,
    val sagaId: String?,
    val messageType: String?,
    val exceptionClass: String?,
    val exceptionMessage: String?,
)
