package com.project.product.exception

import com.google.protobuf.InvalidProtocolBufferException

object NonRetryableExceptions {

    val TYPES: List<Class<out Exception>> = listOf(
        InvalidProtocolBufferException::class.java,
        ArithmeticException::class.java,
        IllegalArgumentException::class.java,
    )

    fun includes(className: String?): Boolean {
        if (className == null) {
            return false
        }
        val type = runCatching { Class.forName(className, false, javaClass.classLoader) }.getOrNull()
            ?: return TYPES.any { it.name == className }
        return TYPES.any { it.isAssignableFrom(type) }
    }
}
