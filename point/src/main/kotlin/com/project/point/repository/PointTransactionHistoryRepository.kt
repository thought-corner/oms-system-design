package com.project.point.repository

import com.project.point.domain.PointTransactionHistory
import com.project.point.domain.PointTransactionType
import org.springframework.data.jpa.repository.JpaRepository

interface PointTransactionHistoryRepository : JpaRepository<PointTransactionHistory, Long> {

    fun findBySagaIdAndTransactionType(
        sagaId: String,
        transactionType: PointTransactionType,
    ): PointTransactionHistory?
}
