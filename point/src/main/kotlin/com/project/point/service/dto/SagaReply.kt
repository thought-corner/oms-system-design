package com.project.point.service.dto

enum class SagaDirection {
    FORWARD, CANCEL
}

enum class SagaOutcome {
    SUCCEEDED, FAILED
}

enum class PointMessageType(val direction: SagaDirection) {
    POINT_USE(SagaDirection.FORWARD),
    POINT_CANCEL(SagaDirection.CANCEL),
}

data class SagaReply(
    val sagaId: String,
    val orderId: Long,
    val step: String,
    val direction: SagaDirection,
    val outcome: SagaOutcome,
    val code: String?,
    val result: Any?,
) {

    companion object {
        const val STEP = "POINT"

        fun succeeded(messageType: PointMessageType, sagaId: String, orderId: Long, result: Any): SagaReply =
            SagaReply(
                sagaId = sagaId,
                orderId = orderId,
                step = STEP,
                direction = messageType.direction,
                outcome = SagaOutcome.SUCCEEDED,
                code = null,
                result = result,
            )

        fun failed(messageType: PointMessageType, sagaId: String, orderId: Long, code: String): SagaReply =
            SagaReply(
                sagaId = sagaId,
                orderId = orderId,
                step = STEP,
                direction = messageType.direction,
                outcome = SagaOutcome.FAILED,
                code = code,
                result = null,
            )
    }
}
