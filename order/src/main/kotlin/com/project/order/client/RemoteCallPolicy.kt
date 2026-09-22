package com.project.order.client

import java.time.Duration

enum class RemoteCallPolicy(
    val timeout: Duration,
    val maxAttempts: Int,
) {
    LOCAL_WRITE(Duration.ofSeconds(1), 3),
    EXTERNAL_APPROVAL(Duration.ofSeconds(5), 2),
    ;

    companion object {
        val BACKOFF: Duration = Duration.ofMillis(300)

        const val COMPENSATION_INLINE_ATTEMPTS: Int = 1

        const val COMPENSATION_WORKER_ATTEMPTS: Int = 3
    }
}
