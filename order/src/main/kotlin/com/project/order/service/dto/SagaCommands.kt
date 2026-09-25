package com.project.order.service.dto

import com.project.order.domain.SagaStep

enum class SagaCommandType(val topic: String) {
    STOCK_BUY("product.command"),
    STOCK_CANCEL("product.command"),
    POINT_USE("point.command"),
    POINT_CANCEL("point.command"),
    PAYMENT_PAY("payment.command"),
    PAYMENT_CANCEL("payment.command"),
    ;

    companion object {
        fun forward(step: SagaStep): SagaCommandType =
            when (step) {
                SagaStep.STOCK -> STOCK_BUY
                SagaStep.POINT -> POINT_USE
                SagaStep.PAYMENT -> PAYMENT_PAY
            }

        fun cancel(step: SagaStep): SagaCommandType =
            when (step) {
                SagaStep.STOCK -> STOCK_CANCEL
                SagaStep.POINT -> POINT_CANCEL
                SagaStep.PAYMENT -> PAYMENT_CANCEL
            }
    }
}
