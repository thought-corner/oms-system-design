package com.project.product.fixture

import com.project.product.domain.Product
import com.project.product.domain.ProductTransactionHistory
import com.project.product.domain.ProductTransactionType
import com.project.product.domain.SagaGuard
import com.project.product.domain.SagaGuardKind
import java.time.LocalDateTime

object ProductFixture {

    val SEED_TIME: LocalDateTime = LocalDateTime.of(2026, 9, 22, 12, 0)

    fun product(
        id: Long = 1L,
        quantity: Long = 100L,
        price: Long = 100L,
    ): Product = Product(id = id, quantity = quantity, price = price)

    fun soldOut(id: Long = 2L, price: Long = 200L): Product = product(id = id, quantity = 0L, price = price)

    fun history(
        sagaId: String = "saga-1",
        orderId: Long = 1L,
        productId: Long = 1L,
        quantity: Long = 3L,
        price: Long = 300L,
        transactionType: ProductTransactionType = ProductTransactionType.PURCHASE,
    ): ProductTransactionHistory = ProductTransactionHistory(
        sagaId = sagaId,
        orderId = orderId,
        productId = productId,
        quantity = quantity,
        price = price,
        transactionType = transactionType,
        createdAt = SEED_TIME,
    )

    fun guard(
        sagaId: String = "saga-1",
        kind: SagaGuardKind = SagaGuardKind.FORWARD,
    ): SagaGuard = SagaGuard(sagaId = sagaId, kind = kind, createdAt = SEED_TIME)
}
