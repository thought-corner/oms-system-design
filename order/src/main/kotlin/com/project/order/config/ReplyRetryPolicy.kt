package com.project.order.config

import com.google.protobuf.InvalidProtocolBufferException

object ReplyRetryPolicy {
    const val REPLY_TOPIC = "saga.replies"
    const val RETRY_TOPIC_SUFFIX = "-retry"
    const val DLT_SUFFIX = "-dlt"
    const val DLT_HANDLER_BEAN = "sagaReplyConsumer"
    const val DLT_HANDLER_METHOD = "onDeadLetter"

    const val BLOCKING_INITIAL_INTERVAL_MILLIS = 1_000L
    const val BLOCKING_MULTIPLIER = 2.0
    const val BLOCKING_RETRIES = 3L

    val RETRY_TOPIC_DELAYS_MILLIS: List<Long> = listOf(60_000L, 300_000L, 1_800_000L)

    val NON_RETRYABLE: List<Class<out Exception>> = listOf(
        InvalidProtocolBufferException::class.java,
        ArithmeticException::class.java,
        IllegalArgumentException::class.java,
    )
}
