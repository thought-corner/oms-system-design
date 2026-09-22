package com.project.order.service.policy

import java.time.Duration

object SagaRecoveryPolicy {

    const val SWEEP_INTERVAL_MS: Long = 10_000

    val STUCK_THRESHOLD: Duration = Duration.ofSeconds(60)

    const val BATCH_SIZE: Int = 20
}
