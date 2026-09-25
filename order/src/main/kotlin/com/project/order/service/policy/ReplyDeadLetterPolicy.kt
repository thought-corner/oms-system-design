package com.project.order.service.policy

import com.google.protobuf.InvalidProtocolBufferException

object ReplyDeadLetterPolicy {

    val POISON_CAUSES: List<Class<out Exception>> = listOf(
        InvalidProtocolBufferException::class.java,
        ArithmeticException::class.java,
        IllegalArgumentException::class.java,
    )
}
