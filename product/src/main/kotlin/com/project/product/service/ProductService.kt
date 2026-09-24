package com.project.product.service

import com.project.product.domain.ProductTransactionHistory
import com.project.product.domain.ProductTransactionType
import com.project.product.domain.SagaGuard
import com.project.product.domain.SagaGuardKind
import com.project.common.exception.BusinessException
import com.project.product.exception.ProductErrorCode
import com.project.product.repository.ProductRepository
import com.project.product.repository.ProductTransactionHistoryRepository
import com.project.product.repository.SagaGuardRepository
import com.project.product.service.dto.BuyCancelCommand
import com.project.product.service.dto.BuyCommand
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDateTime

@Service
class ProductService(
    private val productRepository: ProductRepository,
    private val historyRepository: ProductTransactionHistoryRepository,
    private val guardRepository: SagaGuardRepository,
    private val clock: Clock,
) {

    @Transactional
    fun buy(command: BuyCommand): Long {
        val guard = lockGuard(command.sagaId, SagaGuardKind.FORWARD)
        if (guard.kind == SagaGuardKind.CANCEL ||
            historyRepository.findAllBySagaIdAndTransactionType(command.sagaId, ProductTransactionType.CANCEL).isNotEmpty()
        ) {
            throw BusinessException(ProductErrorCode.SAGA_ALREADY_COMPENSATED, "sagaId=${command.sagaId}")
        }

        val purchased = historyRepository.findAllBySagaIdAndTransactionType(command.sagaId, ProductTransactionType.PURCHASE)
        if (purchased.isNotEmpty()) {
            return purchased.sumOf { it.price }
        }

        return command.items
            .groupBy({ it.productId }, { it.quantity })
            .mapValues { (_, quantities) -> quantities.reduce(Math::addExact) }
            .toSortedMap()
            .entries
            .sumOf { (productId, quantity) ->
                val product = productRepository.findWithLockById(productId)
                    ?: throw BusinessException(ProductErrorCode.PRODUCT_NOT_FOUND, "productId=$productId")

                product.buy(quantity)
                val price = product.calculatePrice(quantity)
                historyRepository.save(history(command.sagaId, command.orderId, productId, quantity, price, ProductTransactionType.PURCHASE))

                price
            }
    }

    @Transactional
    fun cancel(command: BuyCancelCommand): Long {
        lockGuard(command.sagaId, SagaGuardKind.CANCEL)

        val purchased = historyRepository.findAllBySagaIdAndTransactionType(command.sagaId, ProductTransactionType.PURCHASE)
        if (purchased.isEmpty()) {
            return 0L
        }

        val canceled = historyRepository.findAllBySagaIdAndTransactionType(command.sagaId, ProductTransactionType.CANCEL)
        if (canceled.isNotEmpty()) {
            return canceled.sumOf { it.price }
        }

        return purchased.sortedBy { it.productId }.sumOf { purchase ->
            val product = productRepository.findWithLockById(purchase.productId)
                ?: throw BusinessException(ProductErrorCode.PRODUCT_NOT_FOUND, "productId=${purchase.productId}")

            product.restore(purchase.quantity)
            historyRepository.save(history(command.sagaId, purchase.orderId, purchase.productId, purchase.quantity, purchase.price, ProductTransactionType.CANCEL))

            purchase.price
        }
    }

    private fun lockGuard(sagaId: String, kind: SagaGuardKind): SagaGuard {
        guardRepository.insertIfAbsent(sagaId, kind.name, LocalDateTime.now(clock))

        return requireNotNull(guardRepository.findWithLockBySagaId(sagaId)) { "sagaId=$sagaId" }
    }

    private fun history(
        sagaId: String,
        orderId: Long,
        productId: Long,
        quantity: Long,
        price: Long,
        type: ProductTransactionType,
    ) = ProductTransactionHistory(
        sagaId = sagaId,
        orderId = orderId,
        productId = productId,
        quantity = quantity,
        price = price,
        transactionType = type,
        createdAt = LocalDateTime.now(clock),
    )
}
