package com.project.product.service.dto

import com.fasterxml.jackson.annotation.JsonInclude

enum class SagaStep { STOCK }

enum class SagaDirection { FORWARD, CANCEL }

enum class SagaOutcome { SUCCEEDED, FAILED }

enum class ProductCommandType(val step: SagaStep, val direction: SagaDirection) {
    STOCK_BUY(SagaStep.STOCK, SagaDirection.FORWARD),
    STOCK_CANCEL(SagaStep.STOCK, SagaDirection.CANCEL),
    ;

    companion object {
        fun of(messageType: String?): ProductCommandType =
            entries.firstOrNull { it.name == messageType }
                ?: throw IllegalArgumentException("messageType=$messageType")
    }
}

@JsonInclude(JsonInclude.Include.NON_NULL)
data class SagaReply(
    val sagaId: String,
    val orderId: Long,
    val step: SagaStep,
    val direction: SagaDirection,
    val outcome: SagaOutcome,
    val code: String?,
    val result: Any?,
)

data class BuyResult(val totalPrice: Long)

data class BuyCancelResult(val restoredPrice: Long)
