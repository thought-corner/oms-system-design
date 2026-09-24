package com.project.payment.messaging.dto

import com.project.payment.service.dto.PayCancelCommand
import com.project.payment.service.dto.PayCommand

data class PayMessage(
    val sagaId: String,
    val orderId: Long,
    val userId: Long,
    val amount: Long,
) {

    fun toCommand(): PayCommand = PayCommand(sagaId = sagaId, orderId = orderId, userId = userId, amount = amount)
}

data class PayCancelMessage(
    val sagaId: String,
    val orderId: Long,
) {

    fun toCommand(): PayCancelCommand = PayCancelCommand(sagaId = sagaId, orderId = orderId)
}
