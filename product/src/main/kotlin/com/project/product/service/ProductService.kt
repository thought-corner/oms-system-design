package com.project.product.service

import com.project.common.exception.BusinessException
import com.project.common.exception.ErrorCode
import com.project.product.domain.ProductTransactionHistory
import com.project.product.domain.ProductTransactionType
import com.project.product.exception.ProductErrorCode
import com.project.product.repository.ProductRepository
import com.project.product.repository.ProductTransactionHistoryRepository
import com.project.product.service.dto.BuyCancelCommand
import com.project.product.service.dto.BuyCommand
import com.project.product.service.dto.ProductCommandType
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDateTime
import java.util.SortedMap

@Service
class ProductService(
    private val productRepository: ProductRepository,
    private val historyRepository: ProductTransactionHistoryRepository,
    private val sagaGuardLock: SagaGuardLock,
    private val sagaReplyWriter: SagaReplyWriter,
    private val clock: Clock,
) {

    @Transactional
    fun buy(command: BuyCommand) {
        sagaGuardLock.lockForward(command.sagaId)
        if (historyRepository.findAllBySagaIdAndTransactionType(command.sagaId, ProductTransactionType.CANCEL).isNotEmpty()) {
            throw BusinessException(ProductErrorCode.SAGA_ALREADY_COMPENSATED, "sagaId=${command.sagaId}")
        }

        val purchaseHistories = historyRepository.findAllBySagaIdAndTransactionType(command.sagaId, ProductTransactionType.PURCHASE)
        val totalPrice = if (purchaseHistories.isNotEmpty()) purchaseHistories.sumOf { it.price } else purchase(command)

        sagaReplyWriter.succeeded(ProductCommandType.STOCK_BUY, command.sagaId, command.orderId, totalPrice)
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun replyBuyFailed(command: BuyCommand, errorCode: ErrorCode) {
        replyBuyFailed(command.sagaId, command.orderId, errorCode)
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun replyBuyFailed(sagaId: String, orderId: Long, errorCode: ErrorCode) {
        sagaReplyWriter.failed(ProductCommandType.STOCK_BUY, sagaId, orderId, errorCode.code)
    }

    @Transactional
    fun cancel(command: BuyCancelCommand) {
        sagaGuardLock.lockCancel(command.sagaId)
        restore(command.sagaId)
        sagaReplyWriter.succeeded(ProductCommandType.STOCK_CANCEL, command.sagaId, command.orderId)
    }

    private fun purchase(command: BuyCommand): Long {
        var totalPrice = 0L
        for ((productId, quantity) in quantitiesByProductId(command.items)) {
            val product = productRepository.findWithLockById(productId)
                ?: throw BusinessException(ProductErrorCode.PRODUCT_NOT_FOUND, "productId=$productId")

            product.buy(quantity)
            val price = product.calculatePrice(quantity)
            historyRepository.save(
                ProductTransactionHistory.purchase(
                    sagaId = command.sagaId,
                    orderId = command.orderId,
                    productId = productId,
                    quantity = quantity,
                    price = price,
                    createdAt = LocalDateTime.now(clock),
                ),
            )
            totalPrice += price
        }

        return totalPrice
    }

    private fun restore(sagaId: String) {
        val purchaseHistories = historyRepository.findAllBySagaIdAndTransactionType(sagaId, ProductTransactionType.PURCHASE)
        if (purchaseHistories.isEmpty()) {
            return
        }

        if (historyRepository.findAllBySagaIdAndTransactionType(sagaId, ProductTransactionType.CANCEL).isNotEmpty()) {
            return
        }

        for (purchase in purchaseHistories.sortedBy { it.productId }) {
            val product = productRepository.findWithLockById(purchase.productId)
                ?: throw BusinessException(ProductErrorCode.PRODUCT_NOT_FOUND, "productId=${purchase.productId}")

            product.restore(purchase.quantity)
            historyRepository.save(ProductTransactionHistory.cancel(purchase, LocalDateTime.now(clock)))
        }
    }

    private fun quantitiesByProductId(items: List<BuyCommand.Item>): SortedMap<Long, Long> =
        items
            .groupBy({ it.productId }, { it.quantity })
            .mapValues { (_, quantities) -> quantities.reduce(Math::addExact) }
            .toSortedMap()
}
