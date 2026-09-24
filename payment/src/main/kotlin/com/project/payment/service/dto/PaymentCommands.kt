package com.project.payment.service.dto

import com.project.payment.domain.Payment
import java.time.LocalDateTime

data class PayCommand(
    val sagaId: String,
    val orderId: Long,
    val userId: Long,
    val amount: Long,
)

data class PayResult(
    val paymentId: Long,
    val paidAt: LocalDateTime,
) {

    companion object {
        fun from(payment: Payment): PayResult =
            PayResult(paymentId = requireNotNull(payment.id), paidAt = payment.paidAt)
    }
}

data class PayCancelCommand(
    val sagaId: String,
    val orderId: Long,
)
