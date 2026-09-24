package com.project.point.messaging.dto

import com.project.point.service.dto.UseCancelCommand
import com.project.point.service.dto.UseCommand

data class UseMessage(
    val sagaId: String,
    val orderId: Long,
    val userId: Long,
    val amount: Long,
) {

    fun toCommand(): UseCommand = UseCommand(sagaId = sagaId, orderId = orderId, userId = userId, amount = amount)
}

data class UseCancelMessage(
    val sagaId: String,
    val orderId: Long,
) {

    fun toCommand(): UseCancelCommand = UseCancelCommand(sagaId = sagaId, orderId = orderId)
}
