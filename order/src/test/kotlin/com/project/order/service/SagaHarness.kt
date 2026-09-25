package com.project.order.service

import com.project.order.domain.Order
import com.project.order.domain.OrderSaga
import com.project.order.domain.OrderStatus
import com.project.order.domain.OutboxMessage
import com.project.order.fixture.OrderFixture
import com.project.order.repository.OrderItemRepository
import com.project.order.repository.OrderRepository
import com.project.order.repository.OrderSagaRepository
import com.project.order.repository.OutboxMessageRepository
import com.project.order.statemachine.OrderStateMachine
import com.project.order.statemachine.SagaStateMachine
import io.mockk.every
import io.mockk.mockk

class SagaHarness(
    val order: Order = OrderFixture.order(status = OrderStatus.PLACING),
    val saga: OrderSaga = OrderFixture.saga(),
) {
    val orderRepository = mockk<OrderRepository>()
    val sagaRepository = mockk<OrderSagaRepository>()
    val outboxRepository = mockk<OutboxMessageRepository>()
    val orderItemRepository = mockk<OrderItemRepository>()
    val saved = mutableListOf<OutboxMessage>()

    val commandOutbox = SagaCommandOutbox(outboxRepository, orderItemRepository, OrderFixture.FIXED_CLOCK)
    val progress = SagaProgress(OrderStateMachine(), SagaStateMachine(), OrderFixture.FIXED_CLOCK)
    val compensation = SagaCompensation(progress, commandOutbox)

    init {
        every { orderRepository.findWithWaitingLockById(OrderFixture.DEFAULT_ORDER_ID) } returns order
        every { orderRepository.findWithLockById(OrderFixture.DEFAULT_ORDER_ID) } returns order
        every { sagaRepository.findWithWaitingLockBySagaId(saga.sagaId) } returns saga
        every { outboxRepository.save(any()) } answers { firstArg<OutboxMessage>().also { saved += it } }
        every { orderItemRepository.findAllByOrderId(OrderFixture.DEFAULT_ORDER_ID) } returns OrderFixture.itemsInReverseProductOrder()
    }

    val messageTypes: List<String>
        get() = saved.map { it.messageType }

    fun payloadOf(messageType: String): ByteArray = saved.single { it.messageType == messageType }.payload
}
