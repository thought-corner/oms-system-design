package com.project.order.service.dto

import com.project.order.domain.SagaStep

enum class SagaCommandType(val topic: String) {
    STOCK_BUY("cmd.product"),
    STOCK_CANCEL("cmd.product"),
    POINT_USE("cmd.point"),
    POINT_CANCEL("cmd.point"),
    PAYMENT_PAY("cmd.payment"),
    PAYMENT_CANCEL("cmd.payment"),
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
