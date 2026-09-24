package com.project.order.exception

import com.project.common.exception.ErrorResponse
import org.slf4j.LoggerFactory
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.dao.PessimisticLockingFailureException
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
class OrderLockExceptionHandler {

    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(PessimisticLockingFailureException::class)
    fun handleLock(e: PessimisticLockingFailureException): ResponseEntity<ErrorResponse> {
        log.warn("Order row lock not acquired: {}", e.message)
        return ResponseEntity.status(OrderErrorCode.ORDER_LOCKED.status).body(ErrorResponse.of(OrderErrorCode.ORDER_LOCKED))
    }
}
