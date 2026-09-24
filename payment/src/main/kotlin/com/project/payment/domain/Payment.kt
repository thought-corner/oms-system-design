package com.project.payment.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.LocalDateTime

@Entity
@Table(
    name = "payments",
    uniqueConstraints = [
        UniqueConstraint(
            name = Payment.UK_PAID_ORDER_ID,
            columnNames = ["paidOrderId"],
        ),
    ],
)
class Payment(
    @Column(unique = true)
    val sagaId: String,
    val orderId: Long,
    val userId: Long,
    val amount: Long,
    val paidAt: LocalDateTime,
) {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set

    @Enumerated(EnumType.STRING)
    var status: PaymentStatus = PaymentStatus.PAID
        protected set

    var paidOrderId: Long? = orderId
        protected set

    fun isCanceled(): Boolean = status == PaymentStatus.CANCELED

    fun transitionTo(next: PaymentStatus) {
        status = next
        paidOrderId = orderId.takeIf { next == PaymentStatus.PAID }
    }

    companion object {
        const val UK_PAID_ORDER_ID: String = "uk_payments_paid_order_id"
    }
}
