package com.project.payment.config

import com.project.payment.exception.NonRetryableExceptions
import java.time.Duration

object CommandRetryPolicy {

    const val COMMAND_TOPIC: String = "cmd.payment"
    const val RETRY_TOPIC_SUFFIX: String = "-retry"
    const val DLT_SUFFIX: String = "-dlt"
    const val DLT_HANDLER_BEAN: String = "paymentCommandConsumer"
    const val DLT_HANDLER_METHOD: String = "onDeadLetter"

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
