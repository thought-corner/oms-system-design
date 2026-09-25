package com.project.product.config

import com.project.product.exception.NonRetryableExceptions
import org.springframework.util.backoff.BackOff
import org.springframework.util.backoff.BackOffExecution
import org.springframework.util.backoff.ExponentialBackOff
import java.time.Duration

object CommandRetryPolicy {

    const val BLOCKING_ATTEMPTS = 3L
    val BLOCKING_INITIAL_INTERVAL: Duration = Duration.ofSeconds(1)
    const val BLOCKING_MULTIPLIER = 2.0

    val RETRY_TOPIC_DELAYS: List<Duration> = listOf(Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(30))

    const val RETRY_TOPIC_SUFFIX = "-retry"
    const val DLT_SUFFIX = "-dlt"

    val NON_RETRYABLE: List<Class<out Exception>> = NonRetryableExceptions.TYPES

    fun blockingBackOff(): BackOff =
        ExponentialBackOff(BLOCKING_INITIAL_INTERVAL.toMillis(), BLOCKING_MULTIPLIER).apply { maxAttempts = BLOCKING_ATTEMPTS }

    fun retryTopicBackOff(): BackOff = DelayListBackOff(RETRY_TOPIC_DELAYS.map { it.toMillis() })

    val nonBlockingAttempts: Int get() = RETRY_TOPIC_DELAYS.size + 1

    private class DelayListBackOff(private val delays: List<Long>) : BackOff {
        override fun start(): BackOffExecution {
            val remaining = delays.iterator()
            return BackOffExecution { if (remaining.hasNext()) remaining.next() else BackOffExecution.STOP }
        }
    }
}
