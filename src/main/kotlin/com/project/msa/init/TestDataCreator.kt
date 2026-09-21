package com.project.msa.init

import com.project.msa.domain.Point
import com.project.msa.domain.Product
import com.project.msa.repository.PointRepository
import com.project.msa.repository.ProductRepository
import jakarta.annotation.PostConstruct
import org.springframework.stereotype.Component

@Component
class TestDataCreator(
    private val pointRepository: PointRepository,
    private val productRepository: ProductRepository,
) {

    @PostConstruct
    fun createTestData() {
        SEED_USER_IDS.forEach { userId ->
            pointRepository.save(Point(userId = userId, amount = 10000L))
        }

        productRepository.save(Product(quantity = 100L, price = 100L))
        productRepository.save(Product(quantity = 100L, price = 200L))
    }

    companion object {
        val SEED_USER_IDS = listOf(1L, 2L, 3L)
    }
}
