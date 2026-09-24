package com.project.operation.integration

import com.project.operation.client.KafkaProducerPolicy
import org.apache.kafka.clients.producer.ProducerConfig
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.kafka.core.DefaultKafkaProducerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.testcontainers.kafka.KafkaContainer

@TestConfiguration(proxyBeanMethods = false)
class BrokerOutageTestConfig {

    @Bean
    fun outageProducerFactory(kafka: KafkaContainer): DefaultKafkaProducerFactory<String, String> =
        DefaultKafkaProducerFactory(
            KafkaProducerPolicy.configs() + mapOf(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG to kafka.bootstrapServers,
                ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG to FAST_REQUEST_TIMEOUT_MS,
                ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG to FAST_DELIVERY_TIMEOUT_MS,
                ProducerConfig.MAX_BLOCK_MS_CONFIG to FAST_MAX_BLOCK_MS,
            ),
        )

    @Bean
    @Primary
    fun outageKafkaTemplate(outageProducerFactory: DefaultKafkaProducerFactory<String, String>): KafkaTemplate<String, String> =
        KafkaTemplate(outageProducerFactory)

    companion object {
        const val FAST_REQUEST_TIMEOUT_MS = "500"
        const val FAST_DELIVERY_TIMEOUT_MS = "1500"
        const val FAST_MAX_BLOCK_MS = "500"
    }
}
