package com.project.order.messaging.dto

import com.project.order.service.dto.SagaReplyCommand
import tools.jackson.databind.JsonNode

data class SagaReplyMessage(
    val sagaId: String,
    val orderId: Long,
    val step: String,
    val direction: String,
    val outcome: String,
    val code: JsonNode? = null,
    val result: JsonNode? = null,
) {

    fun toCommand(messageType: String?): SagaReplyCommand =
        SagaReplyCommand.of(
            sagaId = sagaId,
            orderId = orderId,
            step = step,
            direction = direction,
            outcome = outcome,
            code = code?.takeIf { it.isString }?.asString(),
            totalPrice = result?.get(TOTAL_PRICE)?.takeIf { it.isIntegralNumber }?.asLong(),
            messageType = messageType,
        )

    companion object {
        private const val TOTAL_PRICE = "totalPrice"
    }
}
