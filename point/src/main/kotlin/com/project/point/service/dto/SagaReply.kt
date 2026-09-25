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
    val direction: SagaDirection,
    val outcome: SagaOutcome,
    val code: String?,
) {

    companion object {

        fun succeeded(messageType: PointMessageType, sagaId: String, orderId: Long): SagaReply =
            SagaReply(
                sagaId = sagaId,
                orderId = orderId,
                direction = messageType.direction,
                outcome = SagaOutcome.SUCCEEDED,
                code = null,
            )

        fun failed(messageType: PointMessageType, sagaId: String, orderId: Long, code: String): SagaReply =
            SagaReply(
                sagaId = sagaId,
                orderId = orderId,
                direction = messageType.direction,
                outcome = SagaOutcome.FAILED,
                code = code,
            )
    }
}
