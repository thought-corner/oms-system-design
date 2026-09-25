package com.project.point.domain

enum class OutboxStatus {
    PENDING,
    PUBLISHED,
    FAILED,
}
