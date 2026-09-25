package com.project.operation.fixture

import com.project.operation.domain.OutboxMessage
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneId

object OutboxFixture {

    val ZONE: ZoneId = ZoneId.of("Asia/Seoul")
    val FIXED_TIME: LocalDateTime = LocalDateTime.of(2026, 9, 25, 10, 0)
    val FIXED_CLOCK: Clock = Clock.fixed(FIXED_TIME.atZone(ZONE).toInstant(), ZONE)

    fun message(id: Long, topic: String = "cmd.product"): OutboxMessage = OutboxMessage(
        id = id,
        messageId = "message-$id",
        topic = topic,
        messageKey = "10",
        sagaId = "saga-$id",
        messageType = "STOCK_BUY",
        payload = byteArrayOf(0x08, id.toByte()),
    )
}
