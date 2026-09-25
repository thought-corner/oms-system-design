package com.project.common.exception

import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.HttpMediaTypeNotAcceptableException
import org.springframework.web.HttpMediaTypeNotSupportedException
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.servlet.resource.NoResourceFoundException

@RestControllerAdvice
class GlobalExceptionHandler {

    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(BusinessException::class)
    fun handleBusiness(e: BusinessException): ResponseEntity<ErrorResponse> {
        log.warn("Business exception: code={}, message={}", e.errorCode.code, e.message)
        return ResponseEntity.status(e.errorCode.status).body(ErrorResponse.of(e.errorCode, e.message))
    }

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleUnreadable(e: HttpMessageNotReadableException): ResponseEntity<ErrorResponse> {
        log.warn("Malformed request body: {}", e.message)
        return ResponseEntity.status(CommonErrorCode.MALFORMED_REQUEST.status).body(ErrorResponse.of(CommonErrorCode.MALFORMED_REQUEST))
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun handleTypeMismatch(e: MethodArgumentTypeMismatchException): ResponseEntity<ErrorResponse> {
        log.warn("Argument type mismatch: name={}, message={}", e.name, e.message)
        return ResponseEntity.status(CommonErrorCode.INVALID_PARAMETER.status).body(ErrorResponse.of(CommonErrorCode.INVALID_PARAMETER))
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException::class)
    fun handleMethodNotSupported(e: HttpRequestMethodNotSupportedException): ResponseEntity<ErrorResponse> {
        log.warn("Method not supported: {}", e.message)
        return ResponseEntity.status(CommonErrorCode.METHOD_NOT_ALLOWED.status).headers(e.headers)
            .body(ErrorResponse.of(CommonErrorCode.METHOD_NOT_ALLOWED))
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException::class)
    fun handleMediaTypeNotSupported(e: HttpMediaTypeNotSupportedException): ResponseEntity<ErrorResponse> {
        log.warn("Media type not supported: {}", e.message)
        return ResponseEntity.status(CommonErrorCode.UNSUPPORTED_MEDIA_TYPE.status).headers(e.headers)
            .body(ErrorResponse.of(CommonErrorCode.UNSUPPORTED_MEDIA_TYPE))
    }

    @ExceptionHandler(HttpMediaTypeNotAcceptableException::class)
    fun handleMediaTypeNotAcceptable(e: HttpMediaTypeNotAcceptableException): ResponseEntity<ErrorResponse> {
        log.warn("Media type not acceptable: {}", e.message)
        return ResponseEntity.status(HttpStatus.NOT_ACCEPTABLE).headers(e.headers).build()
    }

    @ExceptionHandler(NoResourceFoundException::class)
    fun handleNoResource(e: NoResourceFoundException): ResponseEntity<ErrorResponse> {
        log.warn("No resource found: {}", e.message)
        return ResponseEntity.status(CommonErrorCode.RESOURCE_NOT_FOUND.status).headers(e.headers)
            .body(ErrorResponse.of(CommonErrorCode.RESOURCE_NOT_FOUND))
    }

    @ExceptionHandler(Exception::class)
    fun handleUnexpected(e: Exception): ResponseEntity<ErrorResponse> {
        log.error("Unhandled exception", e)
        return ResponseEntity.status(CommonErrorCode.INTERNAL_ERROR.status).body(ErrorResponse.of(CommonErrorCode.INTERNAL_ERROR))
    }
}
