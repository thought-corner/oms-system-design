package com.project.payment.controller

import com.project.payment.controller.dto.PayCancelRequest
import com.project.payment.controller.dto.PayRequest
import com.project.payment.controller.dto.PayResponse
import com.project.payment.service.PaymentService
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

@RestController
class PaymentController(
    private val paymentService: PaymentService,
) {

    @PostMapping("/payment")
    fun pay(@RequestBody request: PayRequest): PayResponse {
        val result = paymentService.pay(request.toCommand())
        return PayResponse(paymentId = result.paymentId, paidAt = result.paidAt)
    }

    @PostMapping("/payment/cancel")
    fun cancel(@RequestBody request: PayCancelRequest) {
        paymentService.cancel(request.toCommand())
    }
}
