package com.project.order.repository

import com.project.order.domain.OutboxMessage
import com.project.order.domain.OutboxStatus
import org.springframework.data.jpa.repository.JpaRepository

interface OutboxMessageRepository : JpaRepository<OutboxMessage, Long> {

    fun findAllBySagaIdAndStatusInOrderByIdAsc(sagaId: String, statuses: Collection<OutboxStatus>): List<OutboxMessage>
}
