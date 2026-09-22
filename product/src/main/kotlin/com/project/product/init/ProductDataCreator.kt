package com.project.product.init

import com.project.product.domain.Product
import com.project.product.repository.ProductRepository
import jakarta.annotation.PostConstruct
import org.springframework.stereotype.Component

@Component
class ProductDataCreator(
    private val productRepository: ProductRepository,
) {

    @PostConstruct
    fun createSeedData() {
        productRepository.save(Product(id = 1L, quantity = 100L, price = 100L))
        productRepository.save(Product(id = 2L, quantity = 100L, price = 200L))
    }
}
