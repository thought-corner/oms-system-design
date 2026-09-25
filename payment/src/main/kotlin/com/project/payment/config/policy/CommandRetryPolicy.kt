package com.project.payment.config.policy

import com.project.payment.exception.NonRetryableExceptions
import java.time.Duration

object CommandRetryPolicy {

    val BLOCKING_INITIAL_INTERVAL: Duration = Duration.ofSeconds(1)
    const val BLOCKING_MULTIPLIER: Double = 2.0
    const val BLOCKING_RETRIES: Long = 3L

    val RETRY_TOPIC_DELAYS: List<Duration> = listOf(
        Duration.ofMinutes(1),
        Duration.ofMinutes(5),
        Duration.ofMinutes(30),
    )

    val NON_RETRYABLE: List<Class<out Exception>> = NonRetryableExceptions.TYPES
}
