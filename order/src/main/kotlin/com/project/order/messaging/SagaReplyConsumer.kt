package com.project.order.messaging

import com.project.order.messaging.dto.SagaReplyMessage
import com.project.order.service.ReplyDeadLetterService
import com.project.order.service.SagaReplyService
import com.project.order.service.dto.DeadLetterCommand
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.support.KafkaHeaders
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

@Component
class SagaReplyConsumer(
    private val sagaReplyService: SagaReplyService,
    private val replyDeadLetterService: ReplyDeadLetterService,
    private val objectMapper: ObjectMapper,
) {

    @KafkaListener(topics = [REPLY_TOPIC])
    fun consume(record: ConsumerRecord<String, String>) {
        val message = objectMapper.readValue(record.value(), SagaReplyMessage::class.java)
        sagaReplyService.handle(message.toCommand(record.header(MESSAGE_TYPE_HEADER)))
    }

    fun onDeadLetter(record: ConsumerRecord<String, String>) {
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

    private fun ConsumerRecord<String, String>.header(name: String): String? =
        headers().lastHeader(name)?.value()?.toString(Charsets.UTF_8)

    companion object {
        const val REPLY_TOPIC = "saga.replies"
        const val SAGA_ID_HEADER = "sagaId"
        const val MESSAGE_TYPE_HEADER = "messageType"
    }
}
