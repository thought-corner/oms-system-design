package com.project.order.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.retrytopic.RetryTopicConfiguration
import org.springframework.kafka.retrytopic.RetryTopicConfigurationBuilder
import org.springframework.kafka.retrytopic.RetryTopicConfigurationSupport
import org.springframework.util.backoff.ExponentialBackOff

@Configuration
class KafkaRetryConfig : RetryTopicConfigurationSupport() {

    override fun configureBlockingRetries(blockingRetries: BlockingRetriesConfigurer) {
        blockingRetries
            .retryOn(Exception::class.java)
            .backOff(
                ExponentialBackOff(ReplyRetryPolicy.BLOCKING_INITIAL_INTERVAL_MILLIS, ReplyRetryPolicy.BLOCKING_MULTIPLIER)
                    .apply { maxAttempts = ReplyRetryPolicy.BLOCKING_RETRIES },
            )
    }

    override fun manageNonBlockingFatalExceptions(nonBlockingFatalExceptions: MutableList<Class<out Throwable>>) {
        nonBlockingFatalExceptions.addAll(ReplyRetryPolicy.NON_RETRYABLE)
    }

    override fun configureCustomizers(customizersConfigurer: CustomizersConfigurer) {
        customizersConfigurer.customizeErrorHandler { errorHandler ->
            errorHandler.addNotRetryableExceptions(*ReplyRetryPolicy.NON_RETRYABLE.toTypedArray())
        }
    }

    @Bean
    fun sagaReplyRetryTopic(template: KafkaTemplate<String, String>): RetryTopicConfiguration =
        RetryTopicConfigurationBuilder.newInstance()
            .maxAttempts(ReplyRetryPolicy.RETRY_TOPIC_DELAYS_MILLIS.size + 1)
            .customBackoff(DelayListBackOff(ReplyRetryPolicy.RETRY_TOPIC_DELAYS_MILLIS))
            .suffixTopicsWithIndexValues()
            .retryTopicSuffix(ReplyRetryPolicy.RETRY_TOPIC_SUFFIX)
            .dltSuffix(ReplyRetryPolicy.DLT_SUFFIX)
            .doNotAutoCreateRetryTopics()
            .includeTopic(ReplyRetryPolicy.REPLY_TOPIC)
            .dltHandlerMethod(ReplyRetryPolicy.DLT_HANDLER_BEAN, ReplyRetryPolicy.DLT_HANDLER_METHOD)
            .create(template)
}
