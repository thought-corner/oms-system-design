package com.project.order.service

import com.project.common.exception.BusinessException
import com.project.order.domain.OrderEvent
import com.project.order.domain.OrderSaga
import com.project.order.exception.OrderErrorCode
import com.project.order.repository.OrderRepository
import com.project.order.repository.OrderSagaRepository
import com.project.order.service.dto.PlaceOrderCommand
import com.project.order.service.dto.PlaceOrderResult
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDateTime
import java.util.UUID

@Service
class OrderPlacementService(
    private val orderRepository: OrderRepository,
    private val orderSagaRepository: OrderSagaRepository,
    private val sagaProgress: SagaProgress,
    private val commandOutbox: SagaCommandOutbox,
    private val clock: Clock,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun place(command: PlaceOrderCommand): PlaceOrderResult {
        val idempotencyKey = validKey(command)
        val order = orderRepository.findWithLockById(command.orderId)
            ?: throw BusinessException(OrderErrorCode.ORDER_NOT_FOUND, "orderId=${command.orderId}")

        if (orderSagaRepository.existsByOrderIdAndIdempotencyKey(command.orderId, idempotencyKey)) {
            log.info("Order placement already accepted: orderId={}, status={}", command.orderId, order.status)
            return PlaceOrderResult(command.orderId)
        }

        sagaProgress.advance(order, OrderEvent.PLACE)

        val saga = orderSagaRepository.save(
            OrderSaga(
                sagaId = UUID.randomUUID().toString(),
                orderId = command.orderId,
                idempotencyKey = idempotencyKey,
                createdAt = LocalDateTime.now(clock),
            ),
        )
        commandOutbox.appendForward(saga, order)

        return PlaceOrderResult(command.orderId)
    }

    private fun validKey(command: PlaceOrderCommand): String {
        val key = command.idempotencyKey
        if (key.isNullOrBlank() || key.length > OrderSaga.IDEMPOTENCY_KEY_MAX_LENGTH) {
            throw BusinessException(OrderErrorCode.INVALID_ORDER, "orderId=${command.orderId}, idempotencyKey=${key?.take(20)}")
        }
        return key
    }
}
