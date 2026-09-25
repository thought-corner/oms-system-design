package com.project.operation.integration

import com.project.operation.client.AlertSender
import io.mockk.mockk
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.kafka.config.TopicBuilder
import org.springframework.kafka.core.KafkaAdmin
import org.testcontainers.kafka.KafkaContainer
import org.testcontainers.mysql.MySQLContainer
import org.testcontainers.utility.MountableFile
import java.io.File

@TestConfiguration(proxyBeanMethods = false)
class IntegrationTestConfig {

    @Bean
    @ServiceConnection
    fun mysql(): MySQLContainer = MySQLContainer("mysql:8.0")
        .withDatabaseName("operation")
        .withUsername("operation_user")
        .withPassword(PASSWORD)
        .withCopyFileToContainer(MountableFile.forHostPath(INFRA_INIT_SQL.absolutePath), "/docker-entrypoint-initdb.d/01-schemas.sql")

    @Bean
    @ServiceConnection
    fun kafka(): KafkaContainer = KafkaContainer("apache/kafka:4.1.0")
        .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false")

    @Bean
    fun topics(): KafkaAdmin.NewTopics = KafkaAdmin.NewTopics(
        TopicBuilder.name(COMMAND_TOPIC).partitions(3).build(),
        TopicBuilder.name(REPLY_TOPIC).partitions(3).build(),
    )

    @Bean
    @Primary
    fun alertSender(): AlertSender = mockk(relaxed = true)

    companion object {
        const val PASSWORD = "1234"
        const val COMMAND_TOPIC = "product.command"
        const val REPLY_TOPIC = "order.reply"
        val INFRA_INIT_SQL = File("../infra/mysql/init/01-schemas.sql")
    }
}
