package com.project.product.config

object KafkaTopics {

    const val COMMAND_TOPIC: String = "cmd.product"
    const val RETRY_TOPIC_SUFFIX: String = "-retry"
    const val DLT_SUFFIX: String = "-dlt"
    const val DLT_HANDLER_BEAN: String = "productCommandConsumer"
    const val DLT_HANDLER_METHOD: String = "onDeadLetter"
}
