package com.project.point.messaging

import com.project.point.service.CommandDeadLetterService
import com.project.point.service.dto.DeadLetterCommand
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.springframework.kafka.support.KafkaHeaders
import org.springframework.stereotype.Component

@Component
class PointCommandDeadLetterHandler(
    private val commandDeadLetterService: CommandDeadLetterService,
) {

    fun handle(record: ConsumerRecord<String, ByteArray>) {
        commandDeadLetterService.handle(
            DeadLetterCommand(
                topic = record.topic(),
                orderId = record.key(),
                sagaId = record.header(MessageHeaders.SAGA_ID),
                messageType = record.header(MessageHeaders.MESSAGE_TYPE),
                exceptionClass = record.header(KafkaHeaders.EXCEPTION_CAUSE_FQCN) ?: record.header(KafkaHeaders.EXCEPTION_FQCN),
                exceptionMessage = record.header(KafkaHeaders.EXCEPTION_MESSAGE),
            ),
        )
    }
}
