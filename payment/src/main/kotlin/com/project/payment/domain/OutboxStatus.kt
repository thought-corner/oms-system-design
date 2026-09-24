package com.project.payment.domain

enum class OutboxStatus {
    PENDING,
    PUBLISHED,
    FAILED,
}
