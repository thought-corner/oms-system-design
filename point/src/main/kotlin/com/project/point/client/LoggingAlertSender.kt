package com.project.point.client

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class LoggingAlertSender : AlertSender {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun send(alert: CommandDeadLetterAlert) {
        log.error(
            "[ALERT] 커맨드가 DLT 에 도착해 사람이 확인해야 합니다. kind={}, failedReplyWritten={}, topic={}, orderId={}, sagaId={}, messageType={}, exception={}, message={}",
            alert.kind,
            alert.failedReplyWritten,
            alert.topic,
            alert.orderId,
            alert.sagaId,
            alert.messageType,
            alert.exceptionClass,
            alert.exceptionMessage,
        )
    }
}
