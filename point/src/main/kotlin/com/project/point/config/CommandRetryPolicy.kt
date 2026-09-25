package com.project.point.config

import com.project.point.exception.NonRetryableExceptions

object CommandRetryPolicy {
    const val COMMAND_TOPIC = "cmd.point"
    const val RETRY_TOPIC_SUFFIX = "-retry"
    const val DLT_SUFFIX = "-dlt"
    const val DEAD_LETTER_HANDLER_BEAN = "pointCommandDeadLetterHandler"
    const val DEAD_LETTER_HANDLER_METHOD = "handle"

    const val BLOCKING_INITIAL_INTERVAL_MILLIS = 1_000L
    const val BLOCKING_MULTIPLIER = 2.0
    const val BLOCKING_RETRIES = 3L

    val RETRY_TOPIC_DELAYS_MILLIS: List<Long> = listOf(60_000L, 300_000L, 1_800_000L)

    val NON_RETRYABLE: List<Class<out Exception>> = NonRetryableExceptions.TYPES
}
