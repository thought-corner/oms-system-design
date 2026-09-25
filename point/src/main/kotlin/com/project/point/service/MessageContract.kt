package com.project.point.service

object MessageContract {
    const val COMMAND_TOPIC: String = "cmd.point"
    const val REPLY_TOPIC: String = "saga.replies"
    const val SAGA_ID_HEADER: String = "sagaId"
    const val MESSAGE_TYPE_HEADER: String = "messageType"
}
