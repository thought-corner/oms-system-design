package com.project.order.controller

import com.project.order.controller.dto.CreateOrderRequest
import com.project.order.controller.dto.CreateOrderResponse
import com.project.order.controller.dto.PlaceOrderRequest
import com.project.order.service.OrderService
import com.project.order.service.SagaOrchestrator
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

@RestController
class OrderController(
    private val orderService: OrderService,
    private val sagaOrchestrator: SagaOrchestrator,
) {

    @PostMapping("/order")
    fun createOrder(@RequestBody request: CreateOrderRequest): CreateOrderResponse {
        val result = orderService.createOrder(request.toCreateOrderCommand())
        return CreateOrderResponse(result.orderId)
    }

    @PostMapping("/order/place")
    fun placeOrder(@RequestBody request: PlaceOrderRequest) {
        sagaOrchestrator.placeOrder(request.toPlaceOrderCommand())
    }
}
