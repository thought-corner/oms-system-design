package com.project.msa.controller

import com.project.msa.controller.dto.CreateOrderRequest
import com.project.msa.controller.dto.CreateOrderResponse
import com.project.msa.controller.dto.PlaceOrderRequest
import com.project.msa.service.OrderService
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

@RestController
class OrderController(
    private val orderService: OrderService,
) {

    @PostMapping("/order")
    fun createOrder(@RequestBody request: CreateOrderRequest): CreateOrderResponse {
        val result = orderService.createOrder(request.toCreateOrderCommand())
        return CreateOrderResponse(result.orderId)
    }

    @PostMapping("/order/place")
    fun placeOrder(@RequestBody request: PlaceOrderRequest) {
        orderService.placeOrder(request.toPlaceOrderCommand())
    }
}
