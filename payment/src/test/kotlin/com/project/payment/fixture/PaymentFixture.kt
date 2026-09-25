package com.project.payment.fixture

import com.project.payment.domain.Payment
import com.project.payment.domain.SagaGuard
import com.project.payment.domain.SagaGuardKind
import com.project.payment.repository.SagaGuardRepository
import com.project.payment.service.dto.PayCommand
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

object PaymentFixture {

    const val DEFAULT_SAGA_ID: String = "saga-1"
    const val DEFAULT_ORDER_ID: Long = 1L
    const val DEFAULT_USER_ID: Long = 1L
    const val DEFAULT_AMOUNT: Long = 400L

    val FIXED_INSTANT: Instant = Instant.parse("2026-09-21T10:00:00Z")
    val FIXED_CLOCK: Clock = Clock.fixed(FIXED_INSTANT, ZoneId.of("UTC"))
    val FIXED_NOW: LocalDateTime = LocalDateTime.of(2026, 9, 21, 10, 0)

    fun payment(
        sagaId: String = DEFAULT_SAGA_ID,
        orderId: Long = DEFAULT_ORDER_ID,
        userId: Long = DEFAULT_USER_ID,
        amount: Long = DEFAULT_AMOUNT,
        paidAt: LocalDateTime = FIXED_NOW,
    ): Payment = Payment(sagaId = sagaId, orderId = orderId, userId = userId, amount = amount, paidAt = paidAt)

    fun payCommand(sagaId: String = DEFAULT_SAGA_ID): PayCommand =
        PayCommand(sagaId = sagaId, orderId = DEFAULT_ORDER_ID, userId = DEFAULT_USER_ID, amount = DEFAULT_AMOUNT)

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
