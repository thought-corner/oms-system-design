package com.project.point.service.dto

data class UseCommand(
    val sagaId: String,
    val orderId: Long,
    val userId: Long,
    val amount: Long,
)

data class UseCancelCommand(
    val sagaId: String,
    val orderId: Long,
)

data class UseCancelResult(val refundedAmount: Long)

data class DeadLetterCommand(
    val topic: String,
    val orderId: String?,
    val sagaId: String?,
    val messageType: String?,
    val exceptionClass: String?,
    val exceptionMessage: String?,
)
