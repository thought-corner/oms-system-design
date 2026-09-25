package com.project.point.messaging.dto

import com.project.message.point.PointCancelCommand
import com.project.message.point.PointUseCommand
import com.project.point.service.dto.UseCancelCommand
import com.project.point.service.dto.UseCommand

fun PointUseCommand.toCommand(): UseCommand {
    requireSagaTarget(sagaId, orderId)
    require(userId > 0) { "userId missing: sagaId=$sagaId" }
    require(amount > 0) { "amount missing: sagaId=$sagaId" }
    return UseCommand(sagaId = sagaId, orderId = orderId, userId = userId, amount = amount)
}

fun PointCancelCommand.toCommand(): UseCancelCommand {
    requireSagaTarget(sagaId, orderId)
    return UseCancelCommand(sagaId = sagaId, orderId = orderId)
}

private fun requireSagaTarget(sagaId: String, orderId: Long) {
    require(sagaId.isNotBlank()) { "sagaId missing" }
    require(orderId > 0) { "orderId missing: sagaId=$sagaId" }
}
