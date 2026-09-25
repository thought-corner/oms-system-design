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
import jakarta.persistence.UniqueConstraint
import java.time.LocalDateTime

@Entity
@Table(
    name = "order_saga",
    indexes = [Index(name = "idx_order_saga_status_updated_at", columnList = "status, updatedAt")],
    uniqueConstraints = [
        UniqueConstraint(name = OrderSaga.UK_ORDER_ID_IDEMPOTENCY_KEY, columnNames = ["order_id", "idempotency_key"]),
    ],
)
class OrderSaga(
    @Column(unique = true)
    val sagaId: String,
    @Column(name = "order_id", nullable = false)
    val orderId: Long,
    @Column(name = "idempotency_key", nullable = false, length = IDEMPOTENCY_KEY_MAX_LENGTH)
    val idempotencyKey: String,
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

    @Column(name = "stock_canceled", nullable = false)
    var stockCanceled: Boolean = false
        protected set

    @Column(name = "point_canceled", nullable = false)
    var pointCanceled: Boolean = false
        protected set

    @Column(name = "payment_canceled", nullable = false)
    var paymentCanceled: Boolean = false
        protected set

    var totalPrice: Long = 0L
        protected set

    @Column(name = "failure_code", length = 50)
    var failureCode: String? = null
        protected set

    var attempts: Int = 0
        protected set

    var lastError: String? = null
        protected set

    var updatedAt: LocalDateTime = createdAt
        protected set

    val allCanceled: Boolean
        get() = stockCanceled && pointCanceled && paymentCanceled

    val pendingCancels: List<SagaStep>
        get() = SagaStep.COMPENSATION_ORDER.filterNot { isCanceled(it) }

    fun isAt(step: SagaStep): Boolean = currentStep == step

    fun canFailAt(step: SagaStep): Boolean = isAt(step) && !paymentDone

    fun isCanceled(step: SagaStep): Boolean =
        when (step) {
            SagaStep.STOCK -> stockCanceled
            SagaStep.POINT -> pointCanceled
            SagaStep.PAYMENT -> paymentCanceled
        }

    fun stockCompleted(totalPrice: Long, at: LocalDateTime) {
        this.stockDone = true
        this.totalPrice = totalPrice
        this.currentStep = SagaStep.POINT
        progressed(at)
    }

    fun pointCompleted(at: LocalDateTime) {
        this.pointDone = true
        this.currentStep = SagaStep.PAYMENT
        progressed(at)
    }

    fun paymentCompleted(at: LocalDateTime) {
        this.paymentDone = true
        progressed(at)
    }

    fun canceled(step: SagaStep, at: LocalDateTime): Boolean {
        if (isCanceled(step)) {
            return false
        }

        when (step) {
            SagaStep.STOCK -> stockCanceled = true
            SagaStep.POINT -> pointCanceled = true
            SagaStep.PAYMENT -> paymentCanceled = true
        }
        progressed(at)
        return true
    }

    fun recordFailure(code: String): Boolean {
        if (failureCode != null) {
            return false
        }

        failureCode = code
        return true
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

    fun resetAttempts() {
        this.attempts = 0
    }

    fun claim(at: LocalDateTime) {
        touch(at)
    }

    private fun progressed(at: LocalDateTime) {
        resetAttempts()
        touch(at)
    }

    private fun touch(at: LocalDateTime) {
        this.updatedAt = at
    }

    companion object {
        const val MAX_ERROR_LENGTH = 255
        const val IDEMPOTENCY_KEY_MAX_LENGTH = 100
        const val UK_ORDER_ID_IDEMPOTENCY_KEY = "uk_order_saga_order_id_idempotency_key"
    }
}
