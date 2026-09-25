package com.project.product.messaging

import com.project.common.exception.BusinessException
import com.project.message.product.StockBuyCommand
import com.project.message.product.StockCancelCommand
import com.project.product.messaging.dto.toCommand
import com.project.product.service.CommandDeadLetterService
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
    private val commandDeadLetterService: CommandDeadLetterService,
) {

    @KafkaListener(topics = [COMMAND_TOPIC])
    fun consume(record: ConsumerRecord<String, ByteArray>) {
        when (ProductCommandType.of(record.header(MESSAGE_TYPE_HEADER))) {
            ProductCommandType.STOCK_BUY -> buy(StockBuyCommand.parseFrom(record.value()).toCommand())
            ProductCommandType.STOCK_CANCEL -> productService.cancel(StockCancelCommand.parseFrom(record.value()).toCommand())
        }
    }

    fun onDeadLetter(record: ConsumerRecord<String, ByteArray>) {
        commandDeadLetterService.handle(
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

    private fun buy(command: BuyCommand) {
        try {
            productService.buy(command)
        } catch (e: BusinessException) {
            productService.replyBuyFailed(command, e.errorCode)
        }
    }

    private fun ConsumerRecord<String, ByteArray>.header(name: String): String? =
        headers().lastHeader(name)?.value()?.toString(Charsets.UTF_8)

    companion object {
        const val COMMAND_TOPIC = "cmd.product"
        const val SAGA_ID_HEADER = "sagaId"
        const val MESSAGE_TYPE_HEADER = "messageType"
    }
}
