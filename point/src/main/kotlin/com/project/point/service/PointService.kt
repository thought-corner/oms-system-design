package com.project.point.service

import com.project.point.domain.PointTransactionHistory
import com.project.point.domain.PointTransactionType
import com.project.point.domain.SagaGuard
import com.project.point.domain.SagaGuardKind
import com.project.common.exception.BusinessException
import com.project.point.exception.PointErrorCode
import com.project.point.repository.PointRepository
import com.project.point.repository.PointTransactionHistoryRepository
import com.project.point.repository.SagaGuardRepository
import com.project.point.service.dto.UseCancelCommand
import com.project.point.service.dto.UseCommand
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDateTime

@Service
class PointService(
    private val pointRepository: PointRepository,
    private val historyRepository: PointTransactionHistoryRepository,
    private val guardRepository: SagaGuardRepository,
    private val clock: Clock,
) {

    @Transactional
    fun use(command: UseCommand) {
        val guard = lockGuard(command.sagaId, SagaGuardKind.FORWARD)
        if (guard.kind == SagaGuardKind.CANCEL ||
            historyRepository.findBySagaIdAndTransactionType(command.sagaId, PointTransactionType.CANCEL) != null
        ) {
            throw BusinessException(PointErrorCode.SAGA_ALREADY_COMPENSATED, "sagaId=${command.sagaId}")
        }

        if (historyRepository.findBySagaIdAndTransactionType(command.sagaId, PointTransactionType.USE) != null) {
            return
        }

        val point = pointRepository.findWithLockByUserId(command.userId)
            ?: throw BusinessException(PointErrorCode.POINT_NOT_FOUND, "userId=${command.userId}")

        point.use(command.amount)
        historyRepository.save(
            history(command.sagaId, command.orderId, command.userId, command.amount, PointTransactionType.USE),
        )
    }

    @Transactional
    fun cancel(command: UseCancelCommand): Long {
        lockGuard(command.sagaId, SagaGuardKind.CANCEL)

        val used = historyRepository.findBySagaIdAndTransactionType(command.sagaId, PointTransactionType.USE)
            ?: return 0L

        val canceled = historyRepository.findBySagaIdAndTransactionType(command.sagaId, PointTransactionType.CANCEL)
        if (canceled != null) {
            return canceled.amount
        }

        val point = pointRepository.findWithLockByUserId(used.userId)
            ?: throw BusinessException(PointErrorCode.POINT_NOT_FOUND, "userId=${used.userId}")

        point.refund(used.amount)
        historyRepository.save(
            history(command.sagaId, used.orderId, used.userId, used.amount, PointTransactionType.CANCEL),
        )

        return used.amount
    }

    private fun lockGuard(sagaId: String, kind: SagaGuardKind): SagaGuard {
        guardRepository.insertIfAbsent(sagaId, kind.name, LocalDateTime.now(clock))

        return requireNotNull(guardRepository.findWithLockBySagaId(sagaId)) { "sagaId=$sagaId" }
    }

    private fun history(
        sagaId: String,
        orderId: Long,
        userId: Long,
        amount: Long,
        type: PointTransactionType,
    ) = PointTransactionHistory(
        sagaId = sagaId,
        orderId = orderId,
        userId = userId,
        amount = amount,
        transactionType = type,
        createdAt = LocalDateTime.now(clock),
    )
}
