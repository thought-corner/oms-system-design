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
        const val COMMAND_TOPIC = "payment.command"
        const val DLT_TOPIC = "payment.command.dlt"
        const val REPLY_TOPIC = "order.reply"
        val RETRY_TOPICS = listOf("payment.command.retry-0", "payment.command.retry-1", "payment.command.retry-2")
        val COMMAND_TOPICS = listOf(COMMAND_TOPIC) + RETRY_TOPICS + DLT_TOPIC
    }
}
