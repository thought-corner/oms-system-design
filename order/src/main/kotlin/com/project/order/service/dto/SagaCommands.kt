package com.project.order.service.dto

import com.project.order.domain.SagaStep

enum class SagaCommandType(val topic: String, val step: SagaStep) {
    STOCK_BUY("cmd.product", SagaStep.STOCK),
    STOCK_CANCEL("cmd.product", SagaStep.STOCK),
    POINT_USE("cmd.point", SagaStep.POINT),
    POINT_CANCEL("cmd.point", SagaStep.POINT),
    PAYMENT_PAY("cmd.payment", SagaStep.PAYMENT),
    PAYMENT_CANCEL("cmd.payment", SagaStep.PAYMENT),
    ;

    companion object {
        fun forwardOf(step: SagaStep): SagaCommandType =
            when (step) {
                SagaStep.STOCK -> STOCK_BUY
                SagaStep.POINT -> POINT_USE
                SagaStep.PAYMENT -> PAYMENT_PAY
            }

        fun cancelOf(step: SagaStep): SagaCommandType =
            when (step) {
                SagaStep.STOCK -> STOCK_CANCEL
                SagaStep.POINT -> POINT_CANCEL
                SagaStep.PAYMENT -> PAYMENT_CANCEL
            }
    }
}
