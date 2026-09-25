package com.project.product.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.retrytopic.RetryTopicConfiguration
import org.springframework.kafka.retrytopic.RetryTopicConfigurationBuilder
import org.springframework.kafka.retrytopic.RetryTopicConfigurationSupport
import org.springframework.scheduling.annotation.EnableScheduling

@Configuration
@EnableScheduling
class KafkaRetryConfig : RetryTopicConfigurationSupport() {

    override fun configureBlockingRetries(blockingRetries: BlockingRetriesConfigurer) {
        blockingRetries
            .retryOn(Exception::class.java)
            .backOff(CommandRetryPolicy.blockingBackOff())
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
    fun commandRetryTopic(template: KafkaTemplate<String, ByteArray>): RetryTopicConfiguration =
        RetryTopicConfigurationBuilder.newInstance()
            .maxAttempts(CommandRetryPolicy.nonBlockingAttempts)
            .customBackoff(CommandRetryPolicy.retryTopicBackOff())
            .suffixTopicsWithIndexValues()
            .retryTopicSuffix(CommandRetryPolicy.RETRY_TOPIC_SUFFIX)
            .dltSuffix(CommandRetryPolicy.DLT_SUFFIX)
            .doNotAutoCreateRetryTopics()
            .includeTopic(COMMAND_TOPIC)
            .dltHandlerMethod(COMMAND_CONSUMER_BEAN, DLT_HANDLER_METHOD)
            .create(template)

    companion object {
        const val COMMAND_TOPIC = "cmd.product"
        const val COMMAND_CONSUMER_BEAN = "productCommandConsumer"
        const val DLT_HANDLER_METHOD = "onDeadLetter"
    }
}
