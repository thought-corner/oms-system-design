package com.project.order.exception

import com.project.common.exception.ErrorResponse
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.dao.PessimisticLockingFailureException
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
class OrderLockExceptionHandler {

    @ExceptionHandler(PessimisticLockingFailureException::class)
    fun handleLock(e: PessimisticLockingFailureException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(OrderErrorCode.ORDER_LOCKED.status).body(ErrorResponse.of(OrderErrorCode.ORDER_LOCKED))
}
