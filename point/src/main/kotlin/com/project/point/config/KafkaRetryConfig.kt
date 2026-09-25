package com.project.point.config

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
class KafkaRetryConfig : RetryTopicConfigurationSupport() {

    override fun configureBlockingRetries(blockingRetries: BlockingRetriesConfigurer) {
        blockingRetries
            .retryOn(Exception::class.java)
            .backOff(
                ExponentialBackOff(CommandRetryPolicy.BLOCKING_INITIAL_INTERVAL_MILLIS, CommandRetryPolicy.BLOCKING_MULTIPLIER)
                    .apply { maxAttempts = CommandRetryPolicy.BLOCKING_RETRIES },
            )
    }

    override fun manageNonBlockingFatalExceptions(nonBlockingFatalExceptions: MutableList<Class<out Throwable>>) {
        nonBlockingFatalExceptions.addAll(CommandRetryPolicy.NON_RETRYABLE)
    }

    override fun configureCustomizers(customizersConfigurer: CustomizersConfigurer) {
        customizersConfigurer.customizeErrorHandler { errorHandler ->
            errorHandler.addNotRetryableExceptions(*CommandRetryPolicy.NON_RETRYABLE.toTypedArray())
        }
    }

    @Bean
    fun pointCommandRetryTopic(template: KafkaTemplate<String, ByteArray>): RetryTopicConfiguration =
        RetryTopicConfigurationBuilder.newInstance()
            .maxAttempts(CommandRetryPolicy.RETRY_TOPIC_DELAYS_MILLIS.size + 1)
            .customBackoff(DelayListBackOff(CommandRetryPolicy.RETRY_TOPIC_DELAYS_MILLIS))
            .suffixTopicsWithIndexValues()
            .retryTopicSuffix(CommandRetryPolicy.RETRY_TOPIC_SUFFIX)
            .dltSuffix(CommandRetryPolicy.DLT_SUFFIX)
            .doNotAutoCreateRetryTopics()
            .includeTopic(CommandRetryPolicy.COMMAND_TOPIC)
            .dltHandlerMethod(CommandRetryPolicy.DEAD_LETTER_HANDLER_BEAN, CommandRetryPolicy.DEAD_LETTER_HANDLER_METHOD)
            .create(template)
}
