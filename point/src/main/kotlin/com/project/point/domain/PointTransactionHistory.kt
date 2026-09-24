package com.project.point.domain

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
    name = "point_transaction_histories",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_point_tx_saga_type",
            columnNames = ["sagaId", "transactionType"],
        ),
    ],
)
class PointTransactionHistory(
    val sagaId: String,
    val orderId: Long,
    val userId: Long,
    val amount: Long,
    @Enumerated(EnumType.STRING)
    val transactionType: PointTransactionType,
    val createdAt: LocalDateTime,
) {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set

    companion object {
        fun use(
            sagaId: String,
            orderId: Long,
            userId: Long,
            amount: Long,
            createdAt: LocalDateTime,
        ): PointTransactionHistory = PointTransactionHistory(
            sagaId = sagaId,
            orderId = orderId,
            userId = userId,
            amount = amount,
            transactionType = PointTransactionType.USE,
            createdAt = createdAt,
        )

        fun cancel(use: PointTransactionHistory, createdAt: LocalDateTime): PointTransactionHistory =
            PointTransactionHistory(
                sagaId = use.sagaId,
                orderId = use.orderId,
                userId = use.userId,
                amount = use.amount,
                transactionType = PointTransactionType.CANCEL,
                createdAt = createdAt,
            )
    }
}
