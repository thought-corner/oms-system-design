package com.project.product.messaging

import com.project.common.exception.BusinessException
import com.project.message.product.StockBuyCommand
import com.project.message.product.StockCancelCommand
import com.project.product.messaging.dto.toCommand
import com.project.product.service.DeadLetterAlertService
import com.project.product.service.MessageContract
import com.project.product.service.ProductService
import com.project.product.service.dto.BuyCommand
import com.project.product.service.dto.DeadLetterCommand
import com.project.product.service.dto.ProductCommandType
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.support.KafkaHeaders
import org.springframework.stereotype.Component

@Component
class ProductCommandConsumer(
    private val productService: ProductService,
    private val deadLetterAlertService: DeadLetterAlertService,
) {

    @KafkaListener(topics = [MessageContract.COMMAND_TOPIC])
    fun onCommand(record: ConsumerRecord<String, ByteArray>) {
        when (messageTypeOf(record)) {
            ProductCommandType.STOCK_BUY -> buy(StockBuyCommand.parseFrom(record.value()).toCommand())
            ProductCommandType.STOCK_CANCEL -> productService.cancel(StockCancelCommand.parseFrom(record.value()).toCommand())
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

    private fun buy(command: BuyCommand) {
        try {
            productService.buy(command)
        } catch (e: BusinessException) {
            productService.replyBuyFailed(command, e.errorCode)
        }
    }

    private fun messageTypeOf(record: ConsumerRecord<String, ByteArray>): ProductCommandType {
        val messageType = record.header(MessageContract.MESSAGE_TYPE_HEADER)
        return ProductCommandType.entries.firstOrNull { it.name == messageType }
            ?: throw IllegalArgumentException("unknown messageType=$messageType")
    }

    private fun ConsumerRecord<String, ByteArray>.header(name: String): String? =
        headers().lastHeader(name)?.value()?.toString(Charsets.UTF_8)
}
