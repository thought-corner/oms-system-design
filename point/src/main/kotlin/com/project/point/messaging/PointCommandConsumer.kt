package com.project.point.messaging

import com.project.common.exception.BusinessException
import com.project.message.point.PointCancelCommand
import com.project.message.point.PointUseCommand
import com.project.point.messaging.dto.toCommand
import com.project.point.service.DeadLetterAlertService
import com.project.point.service.MessageContract
import com.project.point.service.PointService
import com.project.point.service.dto.DeadLetterCommand
import com.project.point.service.dto.PointMessageType
import com.project.point.service.dto.UseCommand
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.support.KafkaHeaders
import org.springframework.stereotype.Component

@Component
class PointCommandConsumer(
    private val pointService: PointService,
    private val deadLetterAlertService: DeadLetterAlertService,
) {

    @KafkaListener(topics = [MessageContract.COMMAND_TOPIC])
    fun onCommand(record: ConsumerRecord<String, ByteArray>) {
        when (messageTypeOf(record)) {
            PointMessageType.POINT_USE -> use(PointUseCommand.parseFrom(record.value()).toCommand())
            PointMessageType.POINT_CANCEL -> pointService.cancel(PointCancelCommand.parseFrom(record.value()).toCommand())
        }
    }

    fun onDeadLetter(record: ConsumerRecord<String, ByteArray>) {
        deadLetterAlertService.handle(
            DeadLetterCommand(
                topic = record.header(KafkaHeaders.ORIGINAL_TOPIC) ?: record.topic(),
                orderId = record.key(),
                sagaId = record.header(MessageContract.SAGA_ID_HEADER),
                messageType = record.header(MessageContract.MESSAGE_TYPE_HEADER),
                exceptionClass = record.header(KafkaHeaders.EXCEPTION_CAUSE_FQCN)
                    ?: record.header(KafkaHeaders.EXCEPTION_FQCN),
                exceptionMessage = record.header(KafkaHeaders.EXCEPTION_MESSAGE),
            ),
        )
    }

    private fun use(command: UseCommand) {
        try {
            pointService.use(command)
        } catch (e: BusinessException) {
            pointService.recordUseFailure(command, e.errorCode)
        }
    }

    private fun messageTypeOf(record: ConsumerRecord<String, ByteArray>): PointMessageType {
        val messageType = record.header(MessageContract.MESSAGE_TYPE_HEADER)
        return PointMessageType.entries.firstOrNull { it.name == messageType }
            ?: throw IllegalArgumentException("unknown messageType=$messageType")
    }

    private fun ConsumerRecord<String, ByteArray>.header(name: String): String? =
        headers().lastHeader(name)?.value()?.toString(Charsets.UTF_8)
}
