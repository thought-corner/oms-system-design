package com.project.operation.service.policy

object PublishedOutboxRetentionPolicy {
    const val RETENTION_DAYS: Long = 7
    const val CLEANUP_CHUNK: Int = 1000
    const val CLEANUP_CRON: String = "0 30 4 * * *"
    const val CLEANUP_ZONE: String = "Asia/Seoul"
}
