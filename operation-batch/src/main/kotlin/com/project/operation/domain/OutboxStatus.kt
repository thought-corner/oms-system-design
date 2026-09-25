package com.project.operation.domain

enum class OutboxStatus {
    PENDING,
    PUBLISHED,
    FAILED,
}
