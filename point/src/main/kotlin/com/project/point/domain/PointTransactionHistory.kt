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
}
