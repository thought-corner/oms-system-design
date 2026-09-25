package com.project.payment.config

import com.project.payment.config.policy.CommandRetryPolicy
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.retrytopic.RetryTopicConfigurationSupport
import org.springframework.util.backoff.ExponentialBackOff

@Configuration
class RetryConfig : RetryTopicConfigurationSupport() {

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
}
