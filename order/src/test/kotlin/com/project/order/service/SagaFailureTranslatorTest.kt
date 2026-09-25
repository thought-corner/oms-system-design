package com.project.order.service

import com.project.common.exception.CommonErrorCode
import com.project.order.domain.SagaStep
import com.project.order.exception.PaymentErrorCode
import com.project.order.exception.PointErrorCode
import com.project.order.exception.ProductErrorCode
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class SagaFailureTranslatorTest : BehaviorSpec({

    Given("참여자별 실패 코드") {
        val cases = listOf(
            Triple(SagaStep.STOCK, "INSUFFICIENT_STOCK", ProductErrorCode.INSUFFICIENT_STOCK),
            Triple(SagaStep.STOCK, "PRODUCT_NOT_FOUND", ProductErrorCode.PRODUCT_NOT_FOUND),
            Triple(SagaStep.POINT, "INSUFFICIENT_POINT", PointErrorCode.INSUFFICIENT_POINT),
            Triple(SagaStep.POINT, "POINT_NOT_FOUND", PointErrorCode.POINT_NOT_FOUND),
            Triple(SagaStep.PAYMENT, "ALREADY_PAID", PaymentErrorCode.ALREADY_PAID),
        )

        cases.forEach { (step, code, expected) ->
            When("$step 단계의 $code 를 번역하면") {
                val failure = SagaFailureTranslator.translate(step, code)

                Then("A-6 order 의 사본 코드 ${expected.code} 가 된다") {
                    failure.errorCode shouldBe expected
                    failure.unknownCodeError.shouldBeNull()
                }
            }
        }
    }

    Given("다른 참여자의 코드가 섞인 응답") {

        When("STOCK 단계에 INSUFFICIENT_POINT 가 오면") {
            val failure = SagaFailureTranslator.translate(SagaStep.STOCK, "INSUFFICIENT_POINT")

            Then("B-14 계약 불일치라 INTERNAL_ERROR 이고 원래 코드를 남긴다") {
                failure.errorCode shouldBe CommonErrorCode.INTERNAL_ERROR
                failure.unknownCodeError shouldContain "step=STOCK, code=INSUFFICIENT_POINT"
            }
        }

        When("SAGA_ALREADY_COMPENSATED 를 번역하려 하면") {
            val failure = SagaFailureTranslator.translate(SagaStep.POINT, "SAGA_ALREADY_COMPENSATED")

            Then("실패 사유 표에 없으므로 INTERNAL_ERROR 이다 — 핸들러가 번역 전에 거른다") {
                failure.errorCode shouldBe CommonErrorCode.INTERNAL_ERROR
                SagaFailureTranslator.isAlreadyCompensated("SAGA_ALREADY_COMPENSATED") shouldBe true
                SagaFailureTranslator.isAlreadyCompensated(null) shouldBe false
            }
        }
    }
})
