package com.project.product.service

import com.project.product.domain.ProductTransactionHistory
import com.project.product.domain.ProductTransactionType
import com.project.common.exception.BusinessException
import com.project.product.exception.ProductErrorCode
import com.project.product.repository.ProductRepository
import com.project.product.repository.ProductTransactionHistoryRepository
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
    private val clock: Clock,
) {

    @Transactional
    fun buy(command: BuyCommand): Long {
        val purchased = historyRepository.findAllBySagaIdAndTransactionType(command.sagaId, ProductTransactionType.PURCHASE)
        if (purchased.isNotEmpty()) {
            return purchased.sumOf { it.price }
        }

        return command.items
            .sortedBy { it.productId }
            .sumOf { item ->
                val product = productRepository.findWithLockById(item.productId)
                    ?: throw BusinessException(ProductErrorCode.PRODUCT_NOT_FOUND, "productId=${item.productId}")

                val price = product.calculatePrice(item.quantity)
                product.buy(item.quantity)
                historyRepository.save(history(command.sagaId, command.orderId, item.productId, item.quantity, price, ProductTransactionType.PURCHASE))

                price
            }
    }

    @Transactional
    fun cancel(command: BuyCancelCommand): Long {
        val purchased = historyRepository.findAllBySagaIdAndTransactionType(command.sagaId, ProductTransactionType.PURCHASE)
        if (purchased.isEmpty()) {
            return 0L
        }

        val canceled = historyRepository.findAllBySagaIdAndTransactionType(command.sagaId, ProductTransactionType.CANCEL)
        if (canceled.isNotEmpty()) {
            return canceled.sumOf { it.price }
        }

        return purchased.sumOf { purchase ->
            val product = productRepository.findWithLockById(purchase.productId)
                ?: throw BusinessException(ProductErrorCode.PRODUCT_NOT_FOUND, "productId=${purchase.productId}")

            product.restore(purchase.quantity)
            historyRepository.save(history(command.sagaId, command.orderId, purchase.productId, purchase.quantity, purchase.price, ProductTransactionType.CANCEL))

            purchase.price
        }
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
