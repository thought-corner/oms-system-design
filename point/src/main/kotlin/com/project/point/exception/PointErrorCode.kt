package com.project.point.exception

import com.project.common.exception.ErrorCode
import org.springframework.http.HttpStatus

enum class PointErrorCode(
    override val status: HttpStatus,
    override val message: String,
) : ErrorCode {
    POINT_NOT_FOUND(HttpStatus.NOT_FOUND, "포인트가 존재하지 않습니다."),
    INSUFFICIENT_POINT(HttpStatus.CONFLICT, "잔액이 부족합니다."),
    SAGA_ALREADY_COMPENSATED(HttpStatus.CONFLICT, "이미 보상된 사가입니다."),
    ;

    override val code: String = name
}
