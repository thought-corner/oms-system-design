package com.project.point.controller.dto

import com.project.point.service.dto.UseCancelCommand
import com.project.point.service.dto.UseCommand

data class UseRequest(
    val sagaId: String,
    val orderId: Long,
    val userId: Long,
    val amount: Long,
) {

    fun toCommand(): UseCommand = UseCommand(sagaId = sagaId, orderId = orderId, userId = userId, amount = amount)
}

data class UseCancelRequest(
    val sagaId: String,
    val orderId: Long,
) {

    fun toCommand(): UseCancelCommand = UseCancelCommand(sagaId = sagaId, orderId = orderId)
}

data class UseCancelResponse(val refundedAmount: Long)
