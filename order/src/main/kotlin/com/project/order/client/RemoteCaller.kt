package com.project.order.client

import com.project.common.exception.BusinessException
import org.slf4j.LoggerFactory
import java.time.Duration

class RemoteCaller(
    private val backoff: Duration,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun <T> call(operation: String, maxAttempts: Int, block: () -> T): T {
        var lastFailure: RuntimeException? = null

        repeat(maxAttempts) { attempt ->
            try {
                return block()
            } catch (e: BusinessException) {
                throw e
            } catch (e: RuntimeException) {
                lastFailure = e
                log.warn("Remote call failed: operation={}, attempt={}/{}, cause={}", operation, attempt + 1, maxAttempts, e.toString())
                if (attempt < maxAttempts - 1) {
                    Thread.sleep(backoff.toMillis())
                }
            }
        }

        throw checkNotNull(lastFailure)
    }
}
