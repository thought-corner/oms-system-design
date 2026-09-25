package com.project.point.integration

import com.project.point.client.AlertSender
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
        *listOf(COMMAND_TOPIC, RETRY_0_TOPIC, "$COMMAND_TOPIC.retry-1", "$COMMAND_TOPIC.retry-2", DLT_TOPIC, REPLY_TOPIC)
            .map { TopicBuilder.name(it).partitions(3).build() }
            .toTypedArray(),
    )

    @Bean
    @Primary
    fun alertSender(): AlertSender = mockk(relaxed = true)

    companion object {
        const val COMMAND_TOPIC = "point.command"
        const val RETRY_0_TOPIC = "point.command.retry-0"
        const val DLT_TOPIC = "point.command.dlt"
        const val REPLY_TOPIC = "order.reply"
    }
}
