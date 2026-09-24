package com.project.point.fixture

import com.project.point.domain.Point
import com.project.point.domain.PointTransactionHistory
import com.project.point.domain.PointTransactionType
import com.project.point.domain.SagaGuard
import com.project.point.domain.SagaGuardKind
import java.time.LocalDateTime

object PointFixture {

    val SEED_TIME: LocalDateTime = LocalDateTime.of(2026, 9, 22, 12, 0)

    fun point(
        userId: Long = 1L,
        amount: Long = 10000L,
        id: Long? = 1L,
    ): Point = Point(userId = userId, amount = amount).let { if (id == null) it else it.withId(id) }

    fun history(
        sagaId: String = "saga-1",
        orderId: Long = 1L,
        userId: Long = 1L,
        amount: Long = 400L,
        transactionType: PointTransactionType = PointTransactionType.USE,
    ): PointTransactionHistory = PointTransactionHistory(
        sagaId = sagaId,
        orderId = orderId,
        userId = userId,
        amount = amount,
        transactionType = transactionType,
        createdAt = SEED_TIME,
    )

    fun guard(
        sagaId: String = "saga-1",
        kind: SagaGuardKind = SagaGuardKind.FORWARD,
    ): SagaGuard = SagaGuard(sagaId = sagaId, kind = kind, createdAt = SEED_TIME)
}
