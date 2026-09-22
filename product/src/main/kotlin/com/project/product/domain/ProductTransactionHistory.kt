package com.project.product.domain

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
    name = "product_transaction_histories",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_product_tx_saga_product_type",
            columnNames = ["sagaId", "productId", "transactionType"],
        ),
    ],
)
class ProductTransactionHistory(
    val sagaId: String,
    val orderId: Long,
    val productId: Long,
    val quantity: Long,
    val price: Long,
    @Enumerated(EnumType.STRING)
    val transactionType: ProductTransactionType,
    val createdAt: LocalDateTime,
) {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set
}
