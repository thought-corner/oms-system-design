package com.project.payment.service

import com.project.common.exception.ErrorCode
import com.project.payment.domain.OutboxMessage
import com.project.payment.repository.OutboxMessageRepository
import com.project.payment.service.dto.PaymentMessageType
import com.project.payment.service.dto.ReplyOutcome
import com.project.payment.service.dto.SagaReply
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.LocalDateTime
import java.util.UUID

@Component
class SagaReplyOutbox(
    private val outboxMessageRepository: OutboxMessageRepository,
    private val jsonMapper: JsonMapper,
    private val clock: Clock,
) {

    fun succeeded(messageType: PaymentMessageType, sagaId: String, orderId: Long, result: Any) {
        append(messageType, reply(messageType, sagaId, orderId, ReplyOutcome.SUCCEEDED, null, result))
    }

    fun failed(messageType: PaymentMessageType, sagaId: String, orderId: Long, errorCode: ErrorCode) {
        append(messageType, reply(messageType, sagaId, orderId, ReplyOutcome.FAILED, errorCode.code, emptyMap<String, Any>()))
    }

    private fun reply(
        messageType: PaymentMessageType,
        sagaId: String,
        orderId: Long,
        outcome: ReplyOutcome,
        code: String?,
        result: Any,
    ): SagaReply = SagaReply(
        sagaId = sagaId,
        orderId = orderId,
        step = MessageContract.STEP,
        direction = messageType.direction,
        outcome = outcome,
        code = code,
        result = result,
    )

    private fun append(messageType: PaymentMessageType, reply: SagaReply) {
        outboxMessageRepository.save(
            OutboxMessage(
                messageId = UUID.randomUUID().toString(),
                topic = MessageContract.REPLY_TOPIC,
                messageKey = reply.orderId.toString(),
                sagaId = reply.sagaId,
                messageType = messageType.name,
                payload = jsonMapper.writeValueAsString(reply),
                occurredAt = LocalDateTime.now(clock),
            ),
        )
    }
}
