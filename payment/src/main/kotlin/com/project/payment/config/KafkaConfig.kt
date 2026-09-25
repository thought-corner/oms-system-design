package com.project.payment.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.retrytopic.RetryTopicConfiguration
import org.springframework.kafka.retrytopic.RetryTopicConfigurationBuilder
import org.springframework.scheduling.annotation.EnableScheduling

@Configuration
@EnableScheduling
class KafkaConfig {

    @Bean
    fun commandRetryTopic(template: KafkaTemplate<String, ByteArray>): RetryTopicConfiguration =
        RetryTopicConfigurationBuilder.newInstance()
            .maxAttempts(CommandRetryPolicy.RETRY_TOPIC_DELAYS.size + 1)
            .customBackoff(DelayListBackOff(CommandRetryPolicy.RETRY_TOPIC_DELAYS.map { it.toMillis() }))
            .suffixTopicsWithIndexValues()
            .retryTopicSuffix(KafkaTopics.RETRY_TOPIC_SUFFIX)
            .dltSuffix(KafkaTopics.DLT_SUFFIX)
            .doNotAutoCreateRetryTopics()
            .includeTopic(KafkaTopics.COMMAND_TOPIC)
            .dltHandlerMethod(KafkaTopics.DLT_HANDLER_BEAN, KafkaTopics.DLT_HANDLER_METHOD)
            .create(template)
}
