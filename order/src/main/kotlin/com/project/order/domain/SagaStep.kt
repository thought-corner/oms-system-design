package com.project.order.domain

enum class SagaStep {
    STOCK,
    POINT,
    PAYMENT,
    ;

    companion object {
        val COMPENSATION_ORDER: List<SagaStep> = listOf(PAYMENT, POINT, STOCK)
    }
}
