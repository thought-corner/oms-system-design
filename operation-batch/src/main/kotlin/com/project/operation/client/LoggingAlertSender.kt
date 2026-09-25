package com.project.operation.client

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class LoggingAlertSender : AlertSender {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun send(alert: OutboxDelayAlert) {
        log.error(
            "[ALERT] outbox 발행이 밀려 사람이 확인해야 합니다. schema={}, pending={}, oldestPendingOccurredAt={}, ageSeconds={}, failed={}",
            alert.schema,
            alert.pending,
            alert.oldestPendingOccurredAt,
            alert.ageSeconds,
            alert.failed,
        )
    }

    override fun send(alert: OutboxPublishFailedAlert) {
        log.error(
            "[ALERT] outbox 행이 발행에 {}회 실패해 FAILED 로 멈췄습니다. 원인을 고친 뒤 PENDING 으로 되돌려야 다시 나갑니다. schema={}, id={}, messageId={}, topic={}, sagaId={}, messageType={}, lastError={}",
            alert.failCount,
            alert.schema,
            alert.outboxId,
            alert.messageId,
            alert.topic,
            alert.sagaId,
            alert.messageType,
            alert.lastError,
        )
    }
}
