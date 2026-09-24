package com.project.order.repository

import com.project.order.domain.OrderSaga
import com.project.order.domain.SagaStatus
import jakarta.persistence.LockModeType
import jakarta.persistence.QueryHint
import org.springframework.data.domain.Limit
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.QueryHints
import java.time.LocalDateTime

interface OrderSagaRepository : JpaRepository<OrderSaga, Long> {

    fun findBySagaId(sagaId: String): OrderSaga?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findWithWaitingLockBySagaId(sagaId: String): OrderSaga?

    fun findByStatusInAndUpdatedAtLessThanOrderByUpdatedAtAsc(
        statuses: Collection<SagaStatus>,
        threshold: LocalDateTime,
        limit: Limit,
    ): List<OrderSaga>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    fun findWithLockBySagaIdAndStatusInAndUpdatedAtLessThan(
        sagaId: String,
        statuses: Collection<SagaStatus>,
        threshold: LocalDateTime,
    ): OrderSaga?
}
