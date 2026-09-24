package com.project.order.service

import com.project.order.client.AlertSender
import com.project.order.client.DeadLetterKind
import com.project.order.client.ReplyDeadLetterAlert
import com.project.order.service.dto.DeadLetterCommand
import com.project.order.service.policy.ReplyDeadLetterPolicy
import org.springframework.stereotype.Service

@Service
class ReplyDeadLetterService(
    private val alertSender: AlertSender,
) {

    fun alert(command: DeadLetterCommand) {
        alertSender.send(
            ReplyDeadLetterAlert(
                kind = kindOf(command.exceptionClass),
                topic = command.topic,
                orderId = command.orderId,
                sagaId = command.sagaId,
                messageType = command.messageType,
                exceptionClass = command.exceptionClass,
                exceptionMessage = command.exceptionMessage,
            ),
        )
    }

    private fun kindOf(exceptionClass: String?): DeadLetterKind {
        val cause = exceptionClass?.let { loadOrNull(it) } ?: return DeadLetterKind.RETRY_EXHAUSTED
        return if (ReplyDeadLetterPolicy.POISON_CAUSES.any { it.isAssignableFrom(cause) }) DeadLetterKind.POISON else DeadLetterKind.RETRY_EXHAUSTED
    }

    private fun loadOrNull(className: String): Class<*>? =
        try {
            Class.forName(className, false, javaClass.classLoader)
        } catch (e: ClassNotFoundException) {
            null
        }
}
