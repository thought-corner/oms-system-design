package com.project.payment.service

import com.project.common.exception.BusinessException
import com.project.payment.domain.SagaGuard
import com.project.payment.domain.SagaGuardKind
import com.project.payment.exception.PaymentErrorCode
import com.project.payment.repository.SagaGuardRepository
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
            throw BusinessException(PaymentErrorCode.SAGA_ALREADY_COMPENSATED, "sagaId=$sagaId")
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
