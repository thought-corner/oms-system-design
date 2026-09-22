package com.project.payment.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDateTime

@Entity
@Table(name = "payments")
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

    fun transitionTo(next: PaymentStatus) {
        status = next
    }
}
