package com.project.payment.messaging.dto

import com.project.message.payment.PaymentCancelCommand
import com.project.message.payment.PaymentPayCommand
import com.project.payment.service.dto.PayCancelCommand
import com.project.payment.service.dto.PayCommand

fun PaymentPayCommand.toCommand(): PayCommand {
    requireSagaTarget(sagaId, orderId)
    require(userId > 0) { "userId missing: sagaId=$sagaId" }
    require(amount > 0) { "amount missing: sagaId=$sagaId" }
    return PayCommand(sagaId = sagaId, orderId = orderId, userId = userId, amount = amount)
}

fun PaymentCancelCommand.toCommand(): PayCancelCommand {
    requireSagaTarget(sagaId, orderId)
    return PayCancelCommand(sagaId = sagaId, orderId = orderId)
}

private fun requireSagaTarget(sagaId: String, orderId: Long) {
    require(sagaId.isNotBlank()) { "sagaId missing" }
    require(orderId > 0) { "orderId missing: sagaId=$sagaId" }
}
