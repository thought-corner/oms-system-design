package com.project.order.service

import com.google.protobuf.MessageLite
import com.project.message.order.PaymentCancelCommand
import com.project.message.order.PaymentPayCommand
import com.project.message.order.PointCancelCommand
import com.project.message.order.PointUseCommand
import com.project.message.order.StockBuyCommand
import com.project.message.order.StockCancelCommand
import com.project.order.domain.Order
import com.project.order.domain.OrderSaga
import com.project.order.domain.OutboxMessage
import com.project.order.domain.OutboxStatus
import com.project.order.domain.SagaStep
import com.project.order.repository.OrderItemRepository
import com.project.order.repository.OutboxMessageRepository
import com.project.order.service.dto.SagaCommandType
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.LocalDateTime
import java.util.UUID

@Component
class SagaCommandOutbox(
    private val outboxMessageRepository: OutboxMessageRepository,
    private val orderItemRepository: OrderItemRepository,
    private val clock: Clock,
) {

    fun appendForward(saga: OrderSaga, order: Order) {
        val type = SagaCommandType.forward(saga.currentStep)
        val payload: MessageLite = when (saga.currentStep) {
            SagaStep.STOCK -> StockBuyCommand.newBuilder()
                .setSagaId(saga.sagaId)
                .setOrderId(saga.orderId)
                .addAllItems(
                    orderItemRepository.findAllByOrderId(saga.orderId)
                        .sortedBy { it.productId }
                        .map { StockBuyCommand.Item.newBuilder().setProductId(it.productId).setQuantity(it.quantity).build() },
                )
                .build()
            SagaStep.POINT -> PointUseCommand.newBuilder()
                .setSagaId(saga.sagaId)
                .setOrderId(saga.orderId)
                .setUserId(order.userId)
                .setAmount(saga.totalPrice)
                .build()
            SagaStep.PAYMENT -> PaymentPayCommand.newBuilder()
                .setSagaId(saga.sagaId)
                .setOrderId(saga.orderId)
                .setUserId(order.userId)
                .setAmount(saga.totalPrice)
                .build()
        }

        append(type, saga, payload)
    }

    fun appendPendingCancels(saga: OrderSaga): List<SagaCommandType> =
        saga.pendingCancels.map { step ->
            SagaCommandType.cancel(step).also { append(it, saga, cancelOf(step, saga)) }
        }

    fun awaitingRelay(saga: OrderSaga, forward: Boolean): List<OutboxMessage> {
        val needed = neededCommands(saga, forward)
        return outboxMessageRepository.findAllBySagaIdAndStatusInOrderByIdAsc(saga.sagaId, UNPUBLISHED)
            .filter { it.status == OutboxStatus.PENDING || it.messageType in needed }
    }

    private fun neededCommands(saga: OrderSaga, forward: Boolean): Set<String> =
        when {
            !forward -> saga.pendingCancels.map { SagaCommandType.cancel(it).name }.toSet()
            saga.paymentDone -> emptySet()
            else -> setOf(SagaCommandType.forward(saga.currentStep).name)
        }

    private fun cancelOf(step: SagaStep, saga: OrderSaga): MessageLite =
        when (step) {
            SagaStep.STOCK -> StockCancelCommand.newBuilder().setSagaId(saga.sagaId).setOrderId(saga.orderId).build()
            SagaStep.POINT -> PointCancelCommand.newBuilder().setSagaId(saga.sagaId).setOrderId(saga.orderId).build()
            SagaStep.PAYMENT -> PaymentCancelCommand.newBuilder().setSagaId(saga.sagaId).setOrderId(saga.orderId).build()
        }

    private fun append(type: SagaCommandType, saga: OrderSaga, payload: MessageLite) {
        outboxMessageRepository.save(
            OutboxMessage(
                messageId = UUID.randomUUID().toString(),
                topic = type.topic,
                messageKey = saga.orderId.toString(),
                sagaId = saga.sagaId,
                messageType = type.name,
                payload = payload.toByteArray(),
                occurredAt = LocalDateTime.now(clock),
            ),
        )
    }

    companion object {
        private val UNPUBLISHED = listOf(OutboxStatus.PENDING, OutboxStatus.FAILED)
    }
}
