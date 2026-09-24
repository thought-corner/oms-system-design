package com.project.order.domain

enum class SagaStatus {
    RUNNING, SUCCEEDED, COMPENSATING, COMPENSATED, COMPENSATION_FAILED
}
