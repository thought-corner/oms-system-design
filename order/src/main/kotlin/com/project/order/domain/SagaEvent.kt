package com.project.order.domain

enum class SagaEvent {
    COMPLETE, COMPENSATE, COMPENSATION_DONE, COMPENSATION_FAIL
}
