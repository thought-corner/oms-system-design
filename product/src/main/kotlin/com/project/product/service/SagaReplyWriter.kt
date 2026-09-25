package com.project.product.service

import com.project.message.product.SagaOutcome
import com.project.message.product.SagaReply
import com.project.product.domain.OutboxMessage
import com.project.product.repository.OutboxMessageRepository
import com.project.product.service.dto.ProductCommandType
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.LocalDateTime
import java.util.UUID

@Component
class SagaReplyWriter(
    private val outboxMessageRepository: OutboxMessageRepository,
    private val clock: Clock,
) {

    fun succeeded(commandType: ProductCommandType, sagaId: String, orderId: Long, totalPrice: Long? = null) {
        val reply = replyOf(commandType, sagaId, orderId, SagaOutcome.SAGA_OUTCOME_SUCCEEDED)
        totalPrice?.let(reply::setTotalPrice)
        append(commandType, reply.build())
    }

    fun failed(commandType: ProductCommandType, sagaId: String, orderId: Long, code: String) {
        append(commandType, replyOf(commandType, sagaId, orderId, SagaOutcome.SAGA_OUTCOME_FAILED).setCode(code).build())
    }

    private fun replyOf(commandType: ProductCommandType, sagaId: String, orderId: Long, outcome: SagaOutcome): SagaReply.Builder =
        SagaReply.newBuilder()
            .setSagaId(sagaId)
            .setOrderId(orderId)
            .setStep(commandType.step)
            .setDirection(commandType.direction)
            .setOutcome(outcome)

    private fun append(commandType: ProductCommandType, reply: SagaReply) {
        outboxMessageRepository.save(
            OutboxMessage(
                messageId = UUID.randomUUID().toString(),
                topic = REPLY_TOPIC,
                messageKey = reply.orderId.toString(),
                sagaId = reply.sagaId,
                messageType = commandType.name,
                payload = reply.toByteArray(),
                occurredAt = LocalDateTime.now(clock),
            ),
        )
    }

    companion object {
        const val REPLY_TOPIC = "saga.replies"
    }
}
