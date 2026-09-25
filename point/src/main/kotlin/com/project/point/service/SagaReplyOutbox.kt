package com.project.point.service

import com.project.message.point.SagaStep
import com.project.point.domain.OutboxMessage
import com.project.point.repository.OutboxMessageRepository
import com.project.point.service.dto.PointMessageType
import com.project.point.service.dto.SagaDirection
import com.project.point.service.dto.SagaOutcome
import com.project.point.service.dto.SagaReply
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.LocalDateTime
import java.util.UUID
import com.project.message.point.SagaDirection as SagaDirectionMessage
import com.project.message.point.SagaOutcome as SagaOutcomeMessage
import com.project.message.point.SagaReply as SagaReplyMessage

@Component
class SagaReplyOutbox(
    private val outboxMessageRepository: OutboxMessageRepository,
    private val clock: Clock,
) {

    fun append(messageType: PointMessageType, reply: SagaReply) {
        outboxMessageRepository.save(
            OutboxMessage(
                messageId = UUID.randomUUID().toString(),
                topic = REPLY_TOPIC,
                messageKey = reply.orderId.toString(),
                sagaId = reply.sagaId,
                messageType = messageType.name,
                payload = toMessage(reply).toByteArray(),
                occurredAt = LocalDateTime.now(clock),
            ),
        )
    }

    private fun toMessage(reply: SagaReply): SagaReplyMessage {
        val builder = SagaReplyMessage.newBuilder()
            .setSagaId(reply.sagaId)
            .setOrderId(reply.orderId)
            .setStep(SagaStep.SAGA_STEP_POINT)
            .setDirection(directionOf(reply.direction))
            .setOutcome(outcomeOf(reply.outcome))
        reply.code?.let(builder::setCode)
        return builder.build()
    }

    private fun directionOf(direction: SagaDirection): SagaDirectionMessage =
        when (direction) {
            SagaDirection.FORWARD -> SagaDirectionMessage.SAGA_DIRECTION_FORWARD
            SagaDirection.CANCEL -> SagaDirectionMessage.SAGA_DIRECTION_CANCEL
        }

    private fun outcomeOf(outcome: SagaOutcome): SagaOutcomeMessage =
        when (outcome) {
            SagaOutcome.SUCCEEDED -> SagaOutcomeMessage.SAGA_OUTCOME_SUCCEEDED
            SagaOutcome.FAILED -> SagaOutcomeMessage.SAGA_OUTCOME_FAILED
        }

    companion object {
        const val REPLY_TOPIC = "saga.replies"
    }
}
