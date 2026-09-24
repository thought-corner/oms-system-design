package com.project.order.service.dto

import com.project.order.domain.SagaStep

enum class ReplyDirection { FORWARD, CANCEL }

enum class ReplyOutcome { SUCCEEDED, FAILED }

data class SagaReplyCommand(
    val sagaId: String,
    val orderId: Long,
    val step: SagaStep,
    val direction: ReplyDirection,
    val outcome: ReplyOutcome,
    val code: String?,
    val totalPrice: Long?,
    val messageType: String?,
) {

    companion object {
        fun of(
            sagaId: String,
            orderId: Long,
            step: String,
            direction: String,
            outcome: String,
            code: String?,
            totalPrice: Long?,
            messageType: String?,
        ): SagaReplyCommand =
            SagaReplyCommand(
                sagaId = sagaId,
                orderId = orderId,
                step = SagaStep.valueOf(step),
                direction = ReplyDirection.valueOf(direction),
                outcome = ReplyOutcome.valueOf(outcome),
                code = code,
                totalPrice = totalPrice,
                messageType = messageType,
            )
    }
}
