package com.project.product.service.dto

import com.project.message.product.SagaDirection
import com.project.message.product.SagaStep

enum class ProductCommandType(val step: SagaStep, val direction: SagaDirection) {
    STOCK_BUY(SagaStep.SAGA_STEP_STOCK, SagaDirection.SAGA_DIRECTION_FORWARD),
    STOCK_CANCEL(SagaStep.SAGA_STEP_STOCK, SagaDirection.SAGA_DIRECTION_CANCEL),
}
