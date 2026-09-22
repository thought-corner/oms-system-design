package com.project.payment.controller.dto

import com.project.payment.service.dto.PayCancelCommand
import com.project.payment.service.dto.PayCommand
import java.time.LocalDateTime

data class PayRequest(
    val sagaId: String,
    val orderId: Long,
    val userId: Long,
    val amount: Long,
) {

    fun toCommand(): PayCommand = PayCommand(sagaId = sagaId, orderId = orderId, userId = userId, amount = amount)
}

data class PayResponse(
    val paymentId: Long,
    val paidAt: LocalDateTime,
)

data class PayCancelRequest(
    val sagaId: String,
    val orderId: Long,
) {

    fun toCommand(): PayCancelCommand = PayCancelCommand(sagaId = sagaId, orderId = orderId)
}
