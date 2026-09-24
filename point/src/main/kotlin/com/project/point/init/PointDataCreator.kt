package com.project.point.init

import com.project.point.domain.Point
import com.project.point.repository.PointRepository
import jakarta.annotation.PostConstruct
import org.springframework.stereotype.Component

@Component
class PointDataCreator(
    private val pointRepository: PointRepository,
) {

    @PostConstruct
    fun createSeedData() {
        SEED_USER_IDS.forEach { userId ->
            pointRepository.save(Point(userId = userId, amount = 10000L))
        }
    }

    companion object {
        val SEED_USER_IDS = listOf(1L, 2L, 3L)
    }
}
