package com.project.common.exception

class BusinessException(
    val errorCode: ErrorCode,
    detail: String? = null,
) : RuntimeException() {

    override val message: String = if (detail == null) errorCode.message else "${errorCode.message} $detail"
}
