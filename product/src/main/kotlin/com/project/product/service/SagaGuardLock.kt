package com.project.product.service

import com.project.common.exception.BusinessException
import com.project.product.domain.SagaGuard
import com.project.product.domain.SagaGuardKind
import com.project.product.exception.ProductErrorCode
import com.project.product.repository.SagaGuardRepository
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.LocalDateTime

@Component
class SagaGuardLock(
    private val guardRepository: SagaGuardRepository,
    private val clock: Clock,
) {

    fun lockForward(sagaId: String) {
        val sagaGuard = lock(sagaId, SagaGuardKind.FORWARD)
        if (sagaGuard.kind == SagaGuardKind.CANCEL) {
            throw BusinessException(ProductErrorCode.SAGA_ALREADY_COMPENSATED, "sagaId=$sagaId")
        }
    }

    fun lockCancel(sagaId: String) {
        lock(sagaId, SagaGuardKind.CANCEL)
    }

    private fun lock(sagaId: String, kind: SagaGuardKind): SagaGuard {
        guardRepository.insertIfAbsent(sagaId, kind.name, LocalDateTime.now(clock))
        return requireNotNull(guardRepository.findWithLockBySagaId(sagaId)) { "sagaId=$sagaId" }
    }
}
