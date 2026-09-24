package com.project.order.service

import com.project.common.exception.CommonErrorCode
import com.project.common.exception.ErrorCode
import com.project.order.domain.SagaStep
import com.project.order.exception.PaymentErrorCode
import com.project.order.exception.PointErrorCode
import com.project.order.exception.ProductErrorCode

object SagaFailureTranslator {

    private val ALREADY_COMPENSATED: String = ProductErrorCode.SAGA_ALREADY_COMPENSATED.code

    private val codesByStep: Map<SagaStep, Map<String, ErrorCode>> = mapOf(
        SagaStep.STOCK to known(ProductErrorCode.entries),
        SagaStep.POINT to known(PointErrorCode.entries),
        SagaStep.PAYMENT to known(PaymentErrorCode.entries),
    )

    fun isAlreadyCompensated(code: String?): Boolean = code == ALREADY_COMPENSATED

    fun translate(step: SagaStep, code: String?): SagaFailure {
        val translated = code?.let { codesByStep.getValue(step)[it] }
            ?: return SagaFailure(CommonErrorCode.INTERNAL_ERROR, "unknown failure code: step=$step, code=$code")

        return SagaFailure(translated, null)
    }

    private fun known(codes: List<ErrorCode>): Map<String, ErrorCode> =
        codes.filterNot { it.code == ALREADY_COMPENSATED }.associateBy { it.code }

    data class SagaFailure(
        val errorCode: ErrorCode,
        val unknownCodeError: String?,
    )
}
