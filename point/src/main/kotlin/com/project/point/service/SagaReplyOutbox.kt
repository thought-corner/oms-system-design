package com.project.point.service

import com.project.common.exception.ErrorCode
import com.project.message.point.SagaDirection
import com.project.message.point.SagaOutcome
import com.project.message.point.SagaReply
import com.project.message.point.SagaStep
import com.project.point.domain.OutboxMessage
import com.project.point.repository.OutboxMessageRepository
import com.project.point.service.dto.PointMessageType
import com.project.point.service.dto.ReplyDirection
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.LocalDateTime
import java.util.UUID

@Component
class SagaReplyOutbox(
    private val outboxMessageRepository: OutboxMessageRepository,
    private val clock: Clock,
) {

    fun succeeded(messageType: PointMessageType, sagaId: String, orderId: Long) {
        append(messageType, reply(messageType, sagaId, orderId, SagaOutcome.SAGA_OUTCOME_SUCCEEDED).build())
    }

    fun failed(messageType: PointMessageType, sagaId: String, orderId: Long, errorCode: ErrorCode) {
        append(messageType, reply(messageType, sagaId, orderId, SagaOutcome.SAGA_OUTCOME_FAILED).setCode(errorCode.code).build())
    }

    private fun reply(
        messageType: PointMessageType,
        sagaId: String,
        orderId: Long,
        outcome: SagaOutcome,
    ): SagaReply.Builder = SagaReply.newBuilder()
        .setSagaId(sagaId)
        .setOrderId(orderId)
        .setStep(SagaStep.SAGA_STEP_POINT)
        .setDirection(directionOf(messageType.direction))
        .setOutcome(outcome)

    private fun directionOf(direction: ReplyDirection): SagaDirection = when (direction) {
        ReplyDirection.FORWARD -> SagaDirection.SAGA_DIRECTION_FORWARD
        ReplyDirection.CANCEL -> SagaDirection.SAGA_DIRECTION_CANCEL
    }

    private fun append(messageType: PointMessageType, reply: SagaReply) {
        outboxMessageRepository.save(
            OutboxMessage(
                messageId = UUID.randomUUID().toString(),
                topic = MessageContract.REPLY_TOPIC,
                messageKey = reply.orderId.toString(),
                sagaId = reply.sagaId,
                messageType = messageType.name,
                payload = reply.toByteArray(),
                occurredAt = LocalDateTime.now(clock),
            ),
        )
    }
}
