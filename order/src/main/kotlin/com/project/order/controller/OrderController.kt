package com.project.order.controller

import com.project.order.controller.dto.CreateOrderRequest
import com.project.order.controller.dto.CreateOrderResponse
import com.project.order.controller.dto.OrderStatusResponse
import com.project.order.controller.dto.PlaceOrderRequest
import com.project.order.service.OrderPlacementService
import com.project.order.service.OrderService
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import java.net.URI

@RestController
class OrderController(
    private val orderService: OrderService,
    private val orderPlacementService: OrderPlacementService,
) {

    @PostMapping("/order")
    fun createOrder(@RequestBody request: CreateOrderRequest): CreateOrderResponse {
        val result = orderService.createOrder(request.toCreateOrderCommand())
        return CreateOrderResponse(result.orderId)
    }

    @PostMapping("/order/place")
    fun placeOrder(
        @RequestBody request: PlaceOrderRequest,
        @RequestHeader(name = IDEMPOTENCY_KEY, required = false) idempotencyKey: String?,
    ): ResponseEntity<Void> {
        val result = orderPlacementService.place(request.toPlaceOrderCommand(idempotencyKey))
        return ResponseEntity.accepted()
            .location(URI.create("/order/${result.orderId}"))
            .header(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS)
            .build()
    }

    @GetMapping("/order/{orderId}")
    fun getOrder(@PathVariable orderId: Long): ResponseEntity<OrderStatusResponse> {
        val result = orderService.findOrder(orderId)
        val response = ResponseEntity.ok()
        if (result.placing) {
            response.header(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS)
        }
        return response.body(OrderStatusResponse.from(result))
    }

    companion object {
        const val IDEMPOTENCY_KEY = "Idempotency-Key"
        const val RETRY_AFTER_SECONDS = "1"
    }
}
