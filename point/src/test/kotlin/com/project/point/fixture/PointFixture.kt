package com.project.point.fixture

import com.project.point.domain.Point
import com.project.point.domain.PointTransactionHistory
import com.project.point.domain.PointTransactionType
import com.project.point.domain.SagaGuard
import com.project.point.domain.SagaGuardKind
import com.project.point.repository.SagaGuardRepository
import com.project.point.service.dto.UseCommand
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneOffset

object PointFixture {

    const val DEFAULT_SAGA_ID: String = "saga-1"
    const val DEFAULT_ORDER_ID: Long = 1L
    const val DEFAULT_USER_ID: Long = 1L
    const val DEFAULT_AMOUNT: Long = 400L

    val FIXED_NOW: LocalDateTime = LocalDateTime.of(2026, 9, 22, 12, 0)
    val FIXED_CLOCK: Clock = Clock.fixed(FIXED_NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC)

    fun point(
        userId: Long = DEFAULT_USER_ID,
        amount: Long = 10000L,
        id: Long? = 1L,
    ): Point = Point(userId = userId, amount = amount).let { if (id == null) it else it.withId(id) }

    fun history(
        sagaId: String = DEFAULT_SAGA_ID,
        orderId: Long = DEFAULT_ORDER_ID,
        userId: Long = DEFAULT_USER_ID,
        amount: Long = DEFAULT_AMOUNT,
        transactionType: PointTransactionType = PointTransactionType.USE,
    ): PointTransactionHistory = PointTransactionHistory(
        sagaId = sagaId,
        orderId = orderId,
        userId = userId,
        amount = amount,
        transactionType = transactionType,
        createdAt = FIXED_NOW,
    )

    fun useCommand(
        sagaId: String = DEFAULT_SAGA_ID,
        userId: Long = DEFAULT_USER_ID,
        amount: Long = DEFAULT_AMOUNT,
    ): UseCommand = UseCommand(sagaId = sagaId, orderId = DEFAULT_ORDER_ID, userId = userId, amount = amount)

    fun guard(
        sagaId: String = DEFAULT_SAGA_ID,
        kind: SagaGuardKind = SagaGuardKind.FORWARD,
    ): SagaGuard = SagaGuard(sagaId = sagaId, kind = kind, createdAt = FIXED_NOW)

    fun guardRepository(lockedKind: SagaGuardKind = SagaGuardKind.FORWARD): SagaGuardRepository =
        mockk<SagaGuardRepository>().also {
            every { it.insertIfAbsent(any(), any(), any()) } just Runs
            every { it.findWithLockBySagaId(any()) } answers { guard(sagaId = firstArg(), kind = lockedKind) }
        }
}

fun PlatformTransactionManager.newTransaction(): TransactionTemplate =
    TransactionTemplate(this).apply {
        propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
    }
