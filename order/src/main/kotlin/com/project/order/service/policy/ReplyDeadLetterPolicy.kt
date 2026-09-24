package com.project.order.service.policy

import tools.jackson.core.JacksonException

object ReplyDeadLetterPolicy {

    val POISON_CAUSES: List<Class<out Exception>> = listOf(
        JacksonException::class.java,
        ArithmeticException::class.java,
        IllegalArgumentException::class.java,
    )
}
