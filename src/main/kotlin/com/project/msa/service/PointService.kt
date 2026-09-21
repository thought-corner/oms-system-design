package com.project.msa.service

import com.project.msa.exception.BusinessException
import com.project.msa.exception.PointErrorCode
import com.project.msa.repository.PointRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class PointService(
    private val pointRepository: PointRepository,
) {

    @Transactional
    fun use(userId: Long, amount: Long) {
        val point = pointRepository.findWithLockByUserId(userId)
            ?: throw BusinessException(PointErrorCode.POINT_NOT_FOUND, "userId=$userId")

        point.use(amount)
    }
}
