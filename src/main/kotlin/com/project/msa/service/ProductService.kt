package com.project.msa.service

import com.project.msa.exception.BusinessException
import com.project.msa.exception.ProductErrorCode
import com.project.msa.repository.ProductRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class ProductService(
    private val productRepository: ProductRepository,
) {

    @Transactional
    fun buy(productId: Long, quantity: Long): Long {
        val product = productRepository.findWithLockById(productId)
            ?: throw BusinessException(ProductErrorCode.PRODUCT_NOT_FOUND, "productId=$productId")

        val totalPrice = product.calculatePrice(quantity)
        product.buy(quantity)

        return totalPrice
    }
}
