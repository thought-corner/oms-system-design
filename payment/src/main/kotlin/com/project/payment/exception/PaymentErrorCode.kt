package com.project.payment.exception

import com.project.common.exception.ErrorCode
import org.springframework.http.HttpStatus

enum class PaymentErrorCode(
    override val status: HttpStatus,
    override val message: String,
) : ErrorCode {
    PAYMENT_FAILED(HttpStatus.CONFLICT, "결제에 실패했습니다."),
    ALREADY_PAID(HttpStatus.CONFLICT, "이미 결제된 주문입니다."),
    INVALID_PAYMENT_STATE_TRANSITION(HttpStatus.CONFLICT, "현재 결제 상태에서 허용되지 않는 전이입니다."),
    SAGA_ALREADY_COMPENSATED(HttpStatus.CONFLICT, "이미 보상된 사가입니다."),
    ;

    override val code: String = name
}
