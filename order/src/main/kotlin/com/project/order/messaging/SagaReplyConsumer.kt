package com.project.order.messaging

import com.project.message.order.SagaReply
import com.project.order.messaging.dto.toCommand
import com.project.order.service.ReplyDeadLetterService
import com.project.order.service.SagaReplyService
import com.project.order.service.dto.DeadLetterCommand
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.support.KafkaHeaders
import org.springframework.stereotype.Component

@Component
class SagaReplyConsumer(
    private val sagaReplyService: SagaReplyService,
    private val replyDeadLetterService: ReplyDeadLetterService,
) {

    @KafkaListener(topics = [REPLY_TOPIC])
    fun consume(record: ConsumerRecord<String, ByteArray>) {
        val reply = SagaReply.parseFrom(requireNotNull(record.value()) { "reply payload missing" })
        sagaReplyService.handle(reply.toCommand(record.header(MESSAGE_TYPE_HEADER)))
    }

    fun onDeadLetter(record: ConsumerRecord<String, ByteArray>) {
        replyDeadLetterService.alert(
            DeadLetterCommand(
                topic = record.topic(),
                orderId = record.key(),
                sagaId = record.header(SAGA_ID_HEADER),
                messageType = record.header(MESSAGE_TYPE_HEADER),
                exceptionClass = record.header(KafkaHeaders.EXCEPTION_CAUSE_FQCN) ?: record.header(KafkaHeaders.EXCEPTION_FQCN),
                exceptionMessage = record.header(KafkaHeaders.EXCEPTION_MESSAGE),
            ),
        )
    }

    private fun ConsumerRecord<String, ByteArray>.header(name: String): String? =
        headers().lastHeader(name)?.value()?.toString(Charsets.UTF_8)

    companion object {
        const val REPLY_TOPIC = "saga.replies"
        const val SAGA_ID_HEADER = "sagaId"
        const val MESSAGE_TYPE_HEADER = "messageType"
    }
}
