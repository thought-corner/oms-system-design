package com.project.product.repository

import com.project.product.domain.ProductTransactionHistory
import com.project.product.domain.ProductTransactionType
import org.springframework.data.jpa.repository.JpaRepository

interface ProductTransactionHistoryRepository : JpaRepository<ProductTransactionHistory, Long> {

    fun findAllBySagaIdAndTransactionType(
        sagaId: String,
        transactionType: ProductTransactionType,
    ): List<ProductTransactionHistory>
}
