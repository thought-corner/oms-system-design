package com.project.point.messaging

import com.project.common.exception.BusinessException
import com.project.point.messaging.dto.UseCancelMessage
import com.project.point.messaging.dto.UseMessage
import com.project.point.service.PointService
import com.project.point.service.dto.PointMessageType
import com.project.point.service.dto.UseCommand
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

@Component
class PointCommandConsumer(
    private val pointService: PointService,
    private val objectMapper: ObjectMapper,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @KafkaListener(topics = [COMMAND_TOPIC])
    fun consume(record: ConsumerRecord<String, String>) {
        when (messageTypeOf(record)) {
            PointMessageType.POINT_USE -> use(objectMapper.readValue(record.value(), UseMessage::class.java).toCommand())
            PointMessageType.POINT_CANCEL ->
                pointService.cancel(objectMapper.readValue(record.value(), UseCancelMessage::class.java).toCommand())
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

    private fun messageTypeOf(record: ConsumerRecord<String, String>): PointMessageType {
        val messageType = record.header(MessageHeaders.MESSAGE_TYPE)
        return PointMessageType.entries.firstOrNull { it.name == messageType }
            ?: throw IllegalArgumentException("messageType=$messageType")
    }

    companion object {
        const val COMMAND_TOPIC = "cmd.point"
    }
}
