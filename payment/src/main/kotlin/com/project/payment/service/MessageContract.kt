package com.project.payment.service

object MessageContract {
    const val COMMAND_TOPIC: String = "payment.command"
    const val REPLY_TOPIC: String = "order.reply"
    const val SAGA_ID_HEADER: String = "sagaId"
    const val MESSAGE_TYPE_HEADER: String = "messageType"
}
