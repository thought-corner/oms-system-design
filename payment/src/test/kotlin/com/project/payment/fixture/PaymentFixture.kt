package com.project.payment.fixture

import com.project.payment.domain.Payment
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
    val FIXED_PAID_AT: LocalDateTime = LocalDateTime.of(2026, 9, 21, 10, 0)

    fun payment(
        sagaId: String = DEFAULT_SAGA_ID,
        orderId: Long = DEFAULT_ORDER_ID,
        userId: Long = DEFAULT_USER_ID,
        amount: Long = DEFAULT_AMOUNT,
        paidAt: LocalDateTime = FIXED_PAID_AT,
    ): Payment = Payment(sagaId = sagaId, orderId = orderId, userId = userId, amount = amount, paidAt = paidAt)
}
