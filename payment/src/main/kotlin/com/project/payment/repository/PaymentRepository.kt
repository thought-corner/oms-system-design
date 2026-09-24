package com.project.payment.repository

import com.project.payment.domain.Payment
import org.springframework.data.jpa.repository.JpaRepository

interface PaymentRepository : JpaRepository<Payment, Long> {

    fun findBySagaId(sagaId: String): Payment?

    fun findByPaidOrderId(paidOrderId: Long): Payment?
}
