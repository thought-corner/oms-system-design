package com.project.operation.client

import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.serialization.StringSerializer
import java.time.Duration

object KafkaProducerPolicy {

    val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(5)
    val DELIVERY_TIMEOUT: Duration = Duration.ofSeconds(20)
    val MAX_BLOCK: Duration = Duration.ofSeconds(5)
    val LINGER: Duration = Duration.ofMillis(5)

    fun configs(): Map<String, Any> = mapOf(
        ProducerConfig.ACKS_CONFIG to "all",
        ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG to "true",
        ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG to StringSerializer::class.java,
        ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG to StringSerializer::class.java,
        ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG to REQUEST_TIMEOUT.toMillis().toString(),
        ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG to DELIVERY_TIMEOUT.toMillis().toString(),
        ProducerConfig.MAX_BLOCK_MS_CONFIG to MAX_BLOCK.toMillis().toString(),
        ProducerConfig.LINGER_MS_CONFIG to LINGER.toMillis().toString(),
    )
}
