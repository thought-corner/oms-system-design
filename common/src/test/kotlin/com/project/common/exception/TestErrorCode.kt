package com.project.common.exception

import org.springframework.http.HttpStatus

enum class TestErrorCode(
    override val status: HttpStatus,
    override val message: String,
) : ErrorCode {
    NOT_FOUND(HttpStatus.NOT_FOUND, "없습니다."),
    BAD_REQUEST(HttpStatus.BAD_REQUEST, "잘못된 요청입니다."),
    CONFLICT(HttpStatus.CONFLICT, "충돌입니다."),
    ;

    override val code: String = name
}
