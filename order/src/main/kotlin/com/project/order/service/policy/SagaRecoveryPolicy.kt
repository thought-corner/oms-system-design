package com.project.order.service.policy

import java.time.Duration

object SagaRecoveryPolicy {

    const val SWEEP_INTERVAL_MS: Long = 10_000

    val STUCK_THRESHOLD: Duration = Duration.ofSeconds(60)

    const val BATCH_SIZE: Int = 20

    const val FORWARD_RECOVERY_ALERT_ATTEMPTS: Int = 3

    const val COMPENSATION_ALERT_ATTEMPTS: Int = 3

    val OUTBOX_STALLED_AGE: Duration = Duration.ofMinutes(5)
}
