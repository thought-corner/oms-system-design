package com.project.operation.service.policy

import java.time.Duration

object OutboxRelayPolicy {
    const val POLL_INTERVAL_MS: Long = 200
    const val BATCH_SIZE: Int = 100
    const val CLAIM_LEASE_SECONDS: Long = 30
    val PUBLISH_DEADLINE: Duration = Duration.ofSeconds(25)

    const val MAX_PUBLISH_ATTEMPTS: Int = 5
    const val LAST_ERROR_MAX_LENGTH: Int = 255
}
