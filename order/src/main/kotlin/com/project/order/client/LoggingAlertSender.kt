package com.project.order.client

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class LoggingAlertSender : AlertSender {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun send(alert: CompensationFailedAlert) {
        log.error(
            "[ALERT] 보상 실패로 사람이 개입해야 합니다. sagaId={}, orderId={}, attempts={}, stuck={}, lastError={}",
            alert.sagaId,
            alert.orderId,
            alert.attempts,
            alert.stuck.ifEmpty { listOf("none") },
            alert.lastError,
        )
    }

    override fun send(alert: ForwardRecoveryFailedAlert) {
        log.error(
            "[ALERT] 결제까지 끝난 주문을 완료로 닫지 못해 사람이 개입해야 합니다. sagaId={}, orderId={}, attempts={}, lastError={}",
            alert.sagaId,
            alert.orderId,
            alert.attempts,
            alert.lastError,
        )
    }
}
