package com.project.msa.exception

class BusinessException(
    val errorCode: ErrorCode,
    detail: String? = null,
) : RuntimeException(if (detail == null) errorCode.message else "${errorCode.message} $detail") {

    override val message: String
        get() = super.message ?: errorCode.message
}
