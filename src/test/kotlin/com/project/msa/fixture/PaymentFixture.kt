package com.project.msa.fixture

import com.project.msa.domain.Payment
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

object PaymentFixture {

    val FIXED_INSTANT: Instant = Instant.parse("2026-09-21T10:00:00Z")
    val FIXED_CLOCK: Clock = Clock.fixed(FIXED_INSTANT, ZoneId.of("UTC"))
    val FIXED_PAID_AT: LocalDateTime = LocalDateTime.of(2026, 9, 21, 10, 0)

    fun payment(
        orderId: Long = OrderFixture.DEFAULT_ORDER_ID,
        userId: Long = OrderFixture.DEFAULT_USER_ID,
        amount: Long = OrderFixture.DEFAULT_TOTAL_PRICE,
        paidAt: LocalDateTime = FIXED_PAID_AT,
    ): Payment = Payment(orderId = orderId, userId = userId, amount = amount, paidAt = paidAt)
}
