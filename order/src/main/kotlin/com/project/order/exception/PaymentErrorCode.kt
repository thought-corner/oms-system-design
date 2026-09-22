package com.project.order.exception

import com.project.common.exception.ErrorCode
import org.springframework.http.HttpStatus

enum class PaymentErrorCode(
    override val status: HttpStatus,
    override val message: String,
) : ErrorCode {
    PAYMENT_FAILED(HttpStatus.CONFLICT, "결제에 실패했습니다."),
    ALREADY_PAID(HttpStatus.CONFLICT, "이미 결제된 주문입니다."),
    ;

    override val code: String = name
}
