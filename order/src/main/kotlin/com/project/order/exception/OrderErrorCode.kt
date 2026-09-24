package com.project.order.exception

import com.project.common.exception.ErrorCode
import org.springframework.http.HttpStatus

enum class OrderErrorCode(
    override val status: HttpStatus,
    override val message: String,
) : ErrorCode {
    ORDER_NOT_FOUND(HttpStatus.NOT_FOUND, "주문정보가 존재하지 않습니다."),
    INVALID_ORDER(HttpStatus.BAD_REQUEST, "주문 항목이 올바르지 않습니다."),
    ORDER_LOCKED(HttpStatus.CONFLICT, "다른 요청이 같은 주문을 결제 중입니다."),
    INVALID_ORDER_STATE_TRANSITION(HttpStatus.CONFLICT, "현재 주문 상태에서 허용되지 않는 전이입니다."),
    INVALID_SAGA_STATE_TRANSITION(HttpStatus.CONFLICT, "현재 사가 상태에서 허용되지 않는 전이입니다."),
    ;

    override val code: String = name
}
