package com.project.common.exception

import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class GlobalExceptionHandler {

    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(BusinessException::class)
    fun handleBusiness(e: BusinessException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(e.errorCode.status).body(ErrorResponse.of(e.errorCode, e.message))

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleUnreadable(e: HttpMessageNotReadableException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(CommonErrorCode.MALFORMED_REQUEST.status).body(ErrorResponse.of(CommonErrorCode.MALFORMED_REQUEST))

    @ExceptionHandler(Exception::class)
    fun handleUnexpected(e: Exception): ResponseEntity<ErrorResponse> {
        if (e is org.springframework.web.ErrorResponse) {
            val status = e.statusCode
            return ResponseEntity.status(status).headers(e.headers)
                .body(ErrorResponse("HTTP_${status.value()}", HttpStatus.resolve(status.value())?.reasonPhrase ?: status.toString()))
        }
        log.error("Unhandled exception", e)
        return ResponseEntity.status(CommonErrorCode.INTERNAL_ERROR.status).body(ErrorResponse.of(CommonErrorCode.INTERNAL_ERROR))
    }
}
