package com.project.msa.repository

import com.project.msa.domain.Point
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock

interface PointRepository : JpaRepository<Point, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findWithLockByUserId(userId: Long): Point?
}
