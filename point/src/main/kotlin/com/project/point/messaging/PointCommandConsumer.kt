package com.project.point.messaging

import com.project.common.exception.BusinessException
import com.project.message.point.PointCancelCommand
import com.project.message.point.PointUseCommand
import com.project.point.messaging.dto.toCommand
import com.project.point.service.PointService
import com.project.point.service.dto.PointMessageType
import com.project.point.service.dto.UseCommand
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

@Component
class PointCommandConsumer(
    private val pointService: PointService,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @KafkaListener(topics = [COMMAND_TOPIC])
    fun consume(record: ConsumerRecord<String, ByteArray>) {
        when (messageTypeOf(record)) {
            PointMessageType.POINT_USE -> use(PointUseCommand.parseFrom(record.value()).toCommand())
            PointMessageType.POINT_CANCEL -> pointService.cancel(PointCancelCommand.parseFrom(record.value()).toCommand())
        }
    }

    private fun use(command: UseCommand) {
        try {
            pointService.use(command)
        } catch (exception: BusinessException) {
            log.warn(
                "Point use rejected. sagaId={}, orderId={}, code={}",
                command.sagaId,
                command.orderId,
                exception.errorCode.code,
            )
            pointService.recordUseFailure(command, exception.errorCode)
        }
    }

    private fun messageTypeOf(record: ConsumerRecord<String, ByteArray>): PointMessageType {
        val messageType = record.header(MessageHeaders.MESSAGE_TYPE)
        return PointMessageType.entries.firstOrNull { it.name == messageType }
            ?: throw IllegalArgumentException("messageType=$messageType")
    }

    companion object {
        const val COMMAND_TOPIC = "cmd.point"
    }
}
