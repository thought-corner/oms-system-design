package com.project.order.messaging.dto

import com.project.message.order.SagaDirection
import com.project.message.order.SagaOutcome
import com.project.message.order.SagaReply
import com.project.message.order.SagaStep
import com.project.order.service.dto.SagaReplyCommand

fun SagaReply.toCommand(messageType: String?): SagaReplyCommand {
    require(sagaId.isNotBlank()) { "sagaId missing" }
    require(orderId > 0) { "orderId missing: sagaId=$sagaId" }
    return SagaReplyCommand.of(
        sagaId = sagaId,
        orderId = orderId,
        step = step.stepName(),
        direction = direction.directionName(),
        outcome = outcome.outcomeName(),
        code = if (hasCode()) code else null,
        totalPrice = if (hasTotalPrice()) totalPrice else null,
        messageType = messageType,
    )
}

private fun SagaStep.stepName(): String =
    when (this) {
        SagaStep.SAGA_STEP_STOCK -> "STOCK"
        SagaStep.SAGA_STEP_POINT -> "POINT"
        SagaStep.SAGA_STEP_PAYMENT -> "PAYMENT"
        SagaStep.SAGA_STEP_UNSPECIFIED, SagaStep.UNRECOGNIZED -> throw IllegalArgumentException("unknown step: $this")
    }

private fun SagaDirection.directionName(): String =
    when (this) {
        SagaDirection.SAGA_DIRECTION_FORWARD -> "FORWARD"
        SagaDirection.SAGA_DIRECTION_CANCEL -> "CANCEL"
        SagaDirection.SAGA_DIRECTION_UNSPECIFIED, SagaDirection.UNRECOGNIZED -> throw IllegalArgumentException("unknown direction: $this")
    }

private fun SagaOutcome.outcomeName(): String =
    when (this) {
        SagaOutcome.SAGA_OUTCOME_SUCCEEDED -> "SUCCEEDED"
        SagaOutcome.SAGA_OUTCOME_FAILED -> "FAILED"
        SagaOutcome.SAGA_OUTCOME_UNSPECIFIED, SagaOutcome.UNRECOGNIZED -> throw IllegalArgumentException("unknown outcome: $this")
    }
