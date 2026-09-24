package com.project.payment.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.retrytopic.RetryTopicConfiguration
import org.springframework.kafka.retrytopic.RetryTopicConfigurationBuilder
import org.springframework.kafka.retrytopic.RetryTopicConfigurationSupport
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.util.backoff.ExponentialBackOff

@Configuration
@EnableScheduling
class KafkaConsumerConfig : RetryTopicConfigurationSupport() {

    override fun configureBlockingRetries(blockingRetries: BlockingRetriesConfigurer) {
        blockingRetries
            .retryOn(Exception::class.java)
            .backOff(
                ExponentialBackOff(
                    CommandRetryPolicy.BLOCKING_INITIAL_INTERVAL.toMillis(),
                    CommandRetryPolicy.BLOCKING_MULTIPLIER,
                ).apply { maxAttempts = CommandRetryPolicy.BLOCKING_RETRIES },
            )
    }

    override fun configureCustomizers(customizersConfigurer: CustomizersConfigurer) {
        customizersConfigurer.customizeErrorHandler { errorHandler ->
            errorHandler.addNotRetryableExceptions(*CommandRetryPolicy.NON_RETRYABLE.toTypedArray())
        }
    }

    override fun manageNonBlockingFatalExceptions(nonBlockingFatalExceptions: MutableList<Class<out Throwable>>) {
        nonBlockingFatalExceptions.addAll(CommandRetryPolicy.NON_RETRYABLE)
    }

    @Bean
    fun commandRetryTopic(template: KafkaTemplate<String, String>): RetryTopicConfiguration =
        RetryTopicConfigurationBuilder.newInstance()
            .maxAttempts(CommandRetryPolicy.RETRY_TOPIC_DELAYS.size + 1)
            .customBackoff(DelayListBackOff(CommandRetryPolicy.RETRY_TOPIC_DELAYS.map { it.toMillis() }))
            .suffixTopicsWithIndexValues()
            .retryTopicSuffix(CommandRetryPolicy.RETRY_TOPIC_SUFFIX)
            .dltSuffix(CommandRetryPolicy.DLT_SUFFIX)
            .doNotAutoCreateRetryTopics()
            .includeTopic(CommandRetryPolicy.COMMAND_TOPIC)
            .dltHandlerMethod(CommandRetryPolicy.DLT_HANDLER_BEAN, CommandRetryPolicy.DLT_HANDLER_METHOD)
            .create(template)
}
