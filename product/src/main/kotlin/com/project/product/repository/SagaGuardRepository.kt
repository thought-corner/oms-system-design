package com.project.product.repository

import com.project.product.domain.SagaGuard
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDateTime

interface SagaGuardRepository : JpaRepository<SagaGuard, String> {

    @Modifying
    @Query(
        value = "INSERT INTO saga_guards (saga_id, kind, created_at) VALUES (:sagaId, :kind, :createdAt) " +
            "ON DUPLICATE KEY UPDATE saga_id = saga_id",
        nativeQuery = true,
    )
    fun insertIfAbsent(
        @Param("sagaId") sagaId: String,
        @Param("kind") kind: String,
        @Param("createdAt") createdAt: LocalDateTime,
    )

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findWithLockBySagaId(sagaId: String): SagaGuard?
}
