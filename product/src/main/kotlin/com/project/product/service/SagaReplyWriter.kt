package com.project.product.service

import com.project.product.domain.OutboxMessage
import com.project.product.repository.OutboxMessageRepository
import com.project.product.service.dto.ProductCommandType
import com.project.product.service.dto.SagaOutcome
import com.project.product.service.dto.SagaReply
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.LocalDateTime
import java.util.UUID

@Component
class SagaReplyWriter(
    private val outboxMessageRepository: OutboxMessageRepository,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
) {

    fun succeeded(commandType: ProductCommandType, sagaId: String, orderId: Long, result: Any) {
        append(commandType, SagaReply(sagaId, orderId, commandType.step, commandType.direction, SagaOutcome.SUCCEEDED, null, result))
    }

    fun failed(commandType: ProductCommandType, sagaId: String, orderId: Long, code: String) {
        append(commandType, SagaReply(sagaId, orderId, commandType.step, commandType.direction, SagaOutcome.FAILED, code, null))
    }

    private fun append(commandType: ProductCommandType, reply: SagaReply) {
        outboxMessageRepository.save(
            OutboxMessage(
                messageId = UUID.randomUUID().toString(),
                topic = REPLY_TOPIC,
                messageKey = reply.orderId.toString(),
                sagaId = reply.sagaId,
                messageType = commandType.name,
                payload = objectMapper.writeValueAsString(reply),
                occurredAt = LocalDateTime.now(clock),
            ),
        )
    }

    companion object {
        const val REPLY_TOPIC = "saga.replies"
    }
}
