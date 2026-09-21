package com.project.msa.exception

data class ErrorResponse(
    val code: String,
    val message: String,
) {

    companion object {
        fun of(errorCode: ErrorCode, message: String = errorCode.message): ErrorResponse =
            ErrorResponse(errorCode.code, message)
    }
}
