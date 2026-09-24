package com.project.point.service

import com.project.point.domain.OutboxMessage
import com.project.point.repository.OutboxMessageRepository
import com.project.point.service.dto.PointMessageType
import com.project.point.service.dto.SagaReply
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.LocalDateTime
import java.util.UUID

@Component
class SagaReplyOutbox(
    private val outboxMessageRepository: OutboxMessageRepository,
    private val objectMapper: ObjectMapper,
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
                payload = objectMapper.writeValueAsString(reply),
                occurredAt = LocalDateTime.now(clock),
            ),
        )
    }

    companion object {
        const val REPLY_TOPIC = "saga.replies"
    }
}
