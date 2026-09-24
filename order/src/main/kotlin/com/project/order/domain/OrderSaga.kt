package com.project.order.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import java.time.LocalDateTime

@Entity
@Table(
    name = "order_saga",
    indexes = [Index(name = "idx_order_saga_status_updated_at", columnList = "status, updatedAt")],
)
class OrderSaga(
    @Column(unique = true)
    val sagaId: String,
    val orderId: Long,
    val createdAt: LocalDateTime,
) {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set

    @Enumerated(EnumType.STRING)
    var status: SagaStatus = SagaStatus.RUNNING
        protected set

    @Enumerated(EnumType.STRING)
    var currentStep: SagaStep = SagaStep.STOCK
        protected set

    var stockDone: Boolean = false
        protected set

    var pointDone: Boolean = false
        protected set

    var paymentDone: Boolean = false
        protected set

    var totalPrice: Long = 0L
        protected set

    var attempts: Int = 0
        protected set

    var lastError: String? = null
        protected set

    var updatedAt: LocalDateTime = createdAt
        protected set

    val isSucceeded: Boolean
        get() = status == SagaStatus.SUCCEEDED

    fun stockCompleted(totalPrice: Long, at: LocalDateTime) {
        this.stockDone = true
        this.totalPrice = totalPrice
        this.currentStep = SagaStep.POINT
        touch(at)
    }

    fun pointCompleted(at: LocalDateTime) {
        this.pointDone = true
        this.currentStep = SagaStep.PAYMENT
        touch(at)
    }

    fun paymentCompleted(at: LocalDateTime) {
        this.paymentDone = true
        touch(at)
    }

    fun transitionTo(next: SagaStatus, at: LocalDateTime) {
        this.status = next
        touch(at)
    }

    fun recordError(error: String?) {
        this.lastError = error?.take(MAX_ERROR_LENGTH)
    }

    fun countAttempt() {
        this.attempts += 1
    }

    fun claim(at: LocalDateTime) {
        touch(at)
    }

    private fun touch(at: LocalDateTime) {
        this.updatedAt = at
    }

    companion object {
        const val MAX_ERROR_LENGTH = 255
    }
}
