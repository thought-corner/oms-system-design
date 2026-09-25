package com.project.product.service

object MessageContract {
    const val COMMAND_TOPIC: String = "cmd.product"
    const val REPLY_TOPIC: String = "saga.replies"
    const val SAGA_ID_HEADER: String = "sagaId"
    const val MESSAGE_TYPE_HEADER: String = "messageType"
}
