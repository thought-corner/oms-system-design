package com.project.point.config

object KafkaTopics {

    const val COMMAND_TOPIC: String = "point.command"
    const val RETRY_TOPIC_SUFFIX: String = ".retry"
    const val DLT_SUFFIX: String = ".dlt"
    const val DLT_HANDLER_BEAN: String = "pointCommandConsumer"
    const val DLT_HANDLER_METHOD: String = "onDeadLetter"
}
