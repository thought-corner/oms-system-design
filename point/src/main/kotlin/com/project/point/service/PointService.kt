package com.project.point.service

import com.project.common.exception.BusinessException
import com.project.common.exception.ErrorCode
import com.project.point.domain.PointTransactionHistory
import com.project.point.domain.PointTransactionType
import com.project.point.exception.PointErrorCode
import com.project.point.repository.PointRepository
import com.project.point.repository.PointTransactionHistoryRepository
import com.project.point.service.dto.PointMessageType
import com.project.point.service.dto.UseCancelCommand
import com.project.point.service.dto.UseCommand
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDateTime

@Service
class PointService(
    private val pointRepository: PointRepository,
    private val historyRepository: PointTransactionHistoryRepository,
    private val sagaGuardLock: SagaGuardLock,
    private val sagaReplyOutbox: SagaReplyOutbox,
    private val clock: Clock,
) {

    @Transactional
    fun use(command: UseCommand) {
        sagaGuardLock.lockForward(command.sagaId)
        if (historyRepository.findBySagaIdAndTransactionType(command.sagaId, PointTransactionType.CANCEL) != null) {
            throw BusinessException(PointErrorCode.SAGA_ALREADY_COMPENSATED, "sagaId=${command.sagaId}")
        }

        if (historyRepository.findBySagaIdAndTransactionType(command.sagaId, PointTransactionType.USE) == null) {
            deduct(command)
        }

        sagaReplyOutbox.succeeded(PointMessageType.POINT_USE, command.sagaId, command.orderId)
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun recordUseFailure(command: UseCommand, errorCode: ErrorCode) {
        recordUseFailure(command.sagaId, command.orderId, errorCode)
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun recordUseFailure(sagaId: String, orderId: Long, errorCode: ErrorCode) {
        sagaReplyOutbox.failed(PointMessageType.POINT_USE, sagaId, orderId, errorCode)
    }

    @Transactional
    fun cancel(command: UseCancelCommand) {
        sagaGuardLock.lockCancel(command.sagaId)
        refund(command)
        sagaReplyOutbox.succeeded(PointMessageType.POINT_CANCEL, command.sagaId, command.orderId)
    }

    private fun deduct(command: UseCommand) {
        val point = pointRepository.findWithLockByUserId(command.userId)
            ?: throw BusinessException(PointErrorCode.POINT_NOT_FOUND, "userId=${command.userId}")

        point.use(command.amount)
        historyRepository.save(
            PointTransactionHistory.use(
                sagaId = command.sagaId,
                orderId = command.orderId,
                userId = command.userId,
                amount = command.amount,
                createdAt = LocalDateTime.now(clock),
            ),
        )
    }

    private fun refund(command: UseCancelCommand) {
        val useHistory = historyRepository.findBySagaIdAndTransactionType(command.sagaId, PointTransactionType.USE)
            ?: return
        if (historyRepository.findBySagaIdAndTransactionType(command.sagaId, PointTransactionType.CANCEL) != null) {
            return
        }

        val point = pointRepository.findWithLockByUserId(useHistory.userId)
            ?: throw BusinessException(PointErrorCode.POINT_NOT_FOUND, "userId=${useHistory.userId}")

        point.refund(useHistory.amount)
        historyRepository.save(PointTransactionHistory.cancel(useHistory, LocalDateTime.now(clock)))
    }
}
