package com.project.payment.messaging

import com.project.common.exception.BusinessException
import com.project.payment.messaging.dto.PayCancelMessage
import com.project.payment.messaging.dto.PayMessage
import com.project.payment.service.DeadLetterAlertService
import com.project.payment.service.MessageContract
import com.project.payment.service.PaymentService
import com.project.payment.service.dto.DeadLetter
import com.project.payment.service.dto.PaymentMessageType
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.support.KafkaHeaders
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper

@Component
class PaymentCommandConsumer(
    private val paymentService: PaymentService,
    private val deadLetterAlertService: DeadLetterAlertService,
    private val jsonMapper: JsonMapper,
) {

    @KafkaListener(topics = [MessageContract.COMMAND_TOPIC])
    fun onCommand(record: ConsumerRecord<String, String>) {
        when (messageTypeOf(record)) {
            PaymentMessageType.PAYMENT_PAY -> pay(jsonMapper.readValue(record.value(), PayMessage::class.java))
            PaymentMessageType.PAYMENT_CANCEL ->
                paymentService.cancel(jsonMapper.readValue(record.value(), PayCancelMessage::class.java).toCommand())
        }
    }

    fun onDeadLetter(record: ConsumerRecord<String, String>) {
        deadLetterAlertService.handle(
            DeadLetter(
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

    private fun pay(message: PayMessage) {
        val command = message.toCommand()
        try {
            paymentService.pay(command)
        } catch (e: BusinessException) {
            paymentService.recordPayFailure(command, e.errorCode)
        }
    }

    private fun messageTypeOf(record: ConsumerRecord<String, String>): PaymentMessageType {
        val messageType = record.header(MessageContract.MESSAGE_TYPE_HEADER)
        return PaymentMessageType.entries.firstOrNull { it.name == messageType }
            ?: throw IllegalArgumentException("unknown messageType=$messageType")
    }

    private fun ConsumerRecord<String, String>.header(name: String): String? =
        headers().lastHeader(name)?.value()?.toString(Charsets.UTF_8)
}
