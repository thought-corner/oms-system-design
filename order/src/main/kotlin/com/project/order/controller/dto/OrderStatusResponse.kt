package com.project.order.controller.dto

import com.fasterxml.jackson.annotation.JsonInclude
import com.project.order.service.dto.OrderStatusResult

@JsonInclude(JsonInclude.Include.NON_NULL)
data class OrderStatusResponse(
    val orderId: Long,
    val status: String,
    val code: String?,
) {

    companion object {
        fun from(result: OrderStatusResult): OrderStatusResponse =
            OrderStatusResponse(orderId = result.orderId, status = result.status, code = result.code)
    }
}
