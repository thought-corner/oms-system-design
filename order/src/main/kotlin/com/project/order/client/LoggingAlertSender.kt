package com.project.order.client

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class LoggingAlertSender : AlertSender {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun send(alert: SagaAlert) {
        when (alert) {
            is ForwardRecoveryFailedAlert -> log.error(
                "[ALERT] 사가가 전진 재발행 뒤에도 진전이 없어 사람이 확인해야 합니다. sagaId={}, orderId={}, step={}, attempts={}, lastError={}",
                alert.sagaId,
                alert.orderId,
                alert.step,
                alert.attempts,
                alert.lastError,
            )
            is CompensationFailedAlert -> log.error(
                "[ALERT] 보상 재발행 뒤에도 응답이 오지 않아 사람이 개입해야 합니다. sagaId={}, orderId={}, attempts={}, pending={}, lastError={}",
                alert.sagaId,
                alert.orderId,
                alert.attempts,
                alert.pendingCancels.ifEmpty { listOf("none") },
                alert.lastError,
            )
            is OutboxStalledAlert -> log.error(
                "[ALERT] 사가의 커맨드가 outbox 에서 발행되지 않고 있습니다. sagaId={}, orderId={}, messageType={}, status={}, occurredAt={}, failedCount={}",
                alert.sagaId,
                alert.orderId,
                alert.messageType,
                alert.status,
                alert.occurredAt,
                alert.failedCount,
            )
            is ReplyDeadLetterAlert -> log.error(
                "[ALERT] 처리하지 못한 응답이 DLT 에 도착해 사람이 확인해야 합니다. kind={}, topic={}, orderId={}, sagaId={}, messageType={}, exception={}, message={}",
                alert.kind,
                alert.topic,
                alert.orderId,
                alert.sagaId,
                alert.messageType,
                alert.exceptionClass,
                alert.exceptionMessage,
            )
        }
    }
}
