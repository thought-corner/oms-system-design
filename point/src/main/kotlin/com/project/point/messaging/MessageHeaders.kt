package com.project.point.messaging

import org.apache.kafka.clients.consumer.ConsumerRecord

object MessageHeaders {
    const val SAGA_ID = "sagaId"
    const val MESSAGE_TYPE = "messageType"
}

fun ConsumerRecord<*, *>.header(name: String): String? =
    headers().lastHeader(name)?.value()?.toString(Charsets.UTF_8)
