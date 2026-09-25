package com.project.order.service

import com.project.order.domain.OrderSaga
import com.project.order.domain.SagaEvent
import com.project.order.service.dto.SagaCommandType
import org.springframework.stereotype.Component

@Component
class SagaCompensation(
    private val sagaProgress: SagaProgress,
    private val commandOutbox: SagaCommandOutbox,
) {

    fun begin(saga: OrderSaga, failureCode: String, error: String?) {
        saga.recordFailure(failureCode)
        error?.let { saga.recordError(it) }
        sagaProgress.advance(saga, SagaEvent.COMPENSATE)
        saga.resetAttempts()
        commandOutbox.appendPendingCancels(saga)
    }

    fun resume(saga: OrderSaga): List<SagaCommandType> {
        sagaProgress.advance(saga, SagaEvent.COMPENSATE)
        return commandOutbox.appendPendingCancels(saga)
    }
}
