package com.project.order.service

import com.project.order.domain.Order
import com.project.order.domain.OrderSaga
import com.project.order.domain.OutboxMessage
import com.project.order.domain.SagaStep
import com.project.order.repository.OrderItemRepository
import com.project.order.repository.OutboxMessageRepository
import com.project.order.service.dto.AmountPayload
import com.project.order.service.dto.CancelPayload
import com.project.order.service.dto.SagaCommandType
import com.project.order.service.dto.StockBuyPayload
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.LocalDateTime
import java.util.UUID

@Component
class SagaCommandOutbox(
    private val outboxMessageRepository: OutboxMessageRepository,
    private val orderItemRepository: OrderItemRepository,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
) {

    fun appendForward(saga: OrderSaga, order: Order) {
        val type = SagaCommandType.forwardOf(saga.currentStep)
        val payload: Any = when (saga.currentStep) {
            SagaStep.STOCK -> StockBuyPayload(
                sagaId = saga.sagaId,
                orderId = saga.orderId,
                items = orderItemRepository.findAllByOrderId(saga.orderId)
                    .sortedBy { it.productId }
                    .map { StockBuyPayload.Item(it.productId, it.quantity) },
            )
            SagaStep.POINT, SagaStep.PAYMENT -> AmountPayload(
                sagaId = saga.sagaId,
                orderId = saga.orderId,
                userId = order.userId,
                amount = saga.totalPrice,
            )
        }

        append(type, saga, payload)
    }

    fun appendPendingCancels(saga: OrderSaga): List<SagaCommandType> =
        saga.pendingCancels.map { step ->
            SagaCommandType.cancelOf(step).also { append(it, saga, CancelPayload(saga.sagaId, saga.orderId)) }
        }

    private fun append(type: SagaCommandType, saga: OrderSaga, payload: Any) {
        outboxMessageRepository.save(
            OutboxMessage(
                messageId = UUID.randomUUID().toString(),
                topic = type.topic,
                messageKey = saga.orderId.toString(),
                sagaId = saga.sagaId,
                messageType = type.name,
                payload = objectMapper.writeValueAsString(payload),
                occurredAt = LocalDateTime.now(clock),
            ),
        )
    }
}
