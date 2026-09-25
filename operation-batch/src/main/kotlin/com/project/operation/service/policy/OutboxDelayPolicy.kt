package com.project.operation.service.policy

import java.time.Duration

object OutboxDelayPolicy {
    const val DELAY_CHECK_INTERVAL_MS: Long = 60_000
    val DELAY_ALERT_AGE: Duration = Duration.ofMinutes(5)
}
