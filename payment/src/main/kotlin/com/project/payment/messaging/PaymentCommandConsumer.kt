package com.project.payment.messaging

import com.project.common.exception.BusinessException
import com.project.message.payment.PaymentCancelCommand
import com.project.message.payment.PaymentPayCommand
import com.project.payment.messaging.dto.toCommand
import com.project.payment.service.DeadLetterAlertService
import com.project.payment.service.MessageContract
import com.project.payment.service.PaymentApproval
import com.project.payment.service.PaymentService
import com.project.payment.service.dto.DeadLetterCommand
import com.project.payment.service.dto.PayCommand
import com.project.payment.service.dto.PaymentMessageType
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.support.KafkaHeaders
import org.springframework.stereotype.Component

@Component
class PaymentCommandConsumer(
    private val paymentService: PaymentService,
    private val paymentApproval: PaymentApproval,
    private val deadLetterAlertService: DeadLetterAlertService,
) {

    @KafkaListener(topics = [MessageContract.COMMAND_TOPIC])
    fun onCommand(record: ConsumerRecord<String, ByteArray>) {
        when (messageTypeOf(record)) {
            PaymentMessageType.PAYMENT_PAY -> pay(PaymentPayCommand.parseFrom(record.value()).toCommand())
            PaymentMessageType.PAYMENT_CANCEL -> paymentService.cancel(PaymentCancelCommand.parseFrom(record.value()).toCommand())
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

    private fun pay(command: PayCommand) {
        paymentApproval.await(command)
        try {
            paymentService.pay(command)
        } catch (e: BusinessException) {
            paymentService.recordPayFailure(command, e.errorCode)
        }
    }

    private fun messageTypeOf(record: ConsumerRecord<String, ByteArray>): PaymentMessageType {
        val messageType = record.header(MessageContract.MESSAGE_TYPE_HEADER)
        return PaymentMessageType.entries.firstOrNull { it.name == messageType }
            ?: throw IllegalArgumentException("unknown messageType=$messageType")
    }

    private fun ConsumerRecord<String, ByteArray>.header(name: String): String? =
        headers().lastHeader(name)?.value()?.toString(Charsets.UTF_8)
}
