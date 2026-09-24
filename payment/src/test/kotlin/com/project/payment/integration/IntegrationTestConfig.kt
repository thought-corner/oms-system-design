package com.project.payment.integration

import com.project.payment.client.AlertSender
import io.mockk.mockk
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.kafka.config.TopicBuilder
import org.springframework.kafka.core.KafkaAdmin
import org.testcontainers.kafka.KafkaContainer

@TestConfiguration(proxyBeanMethods = false)
class IntegrationTestConfig {

    @Bean
    @ServiceConnection
    fun kafka(): KafkaContainer = KafkaContainer("apache/kafka:4.1.0")
        .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false")

    @Bean
    fun topics(): KafkaAdmin.NewTopics = KafkaAdmin.NewTopics(
        *(COMMAND_TOPICS + REPLY_TOPIC).map { TopicBuilder.name(it).partitions(PARTITIONS).build() }.toTypedArray(),
    )

    @Bean
    @Primary
    fun alertSender(): AlertSender = mockk(relaxed = true)

    companion object {
        const val PARTITIONS = 3
        const val COMMAND_TOPIC = "cmd.payment"
        const val DLT_TOPIC = "cmd.payment-dlt"
        const val REPLY_TOPIC = "saga.replies"
        val RETRY_TOPICS = listOf("cmd.payment-retry-0", "cmd.payment-retry-1", "cmd.payment-retry-2")
        val COMMAND_TOPICS = listOf(COMMAND_TOPIC) + RETRY_TOPICS + DLT_TOPIC
    }
}
