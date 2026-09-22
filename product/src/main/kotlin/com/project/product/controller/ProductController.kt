package com.project.product.controller

import com.project.product.controller.dto.BuyCancelRequest
import com.project.product.controller.dto.BuyCancelResponse
import com.project.product.controller.dto.BuyRequest
import com.project.product.controller.dto.BuyResponse
import com.project.product.service.ProductService
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

@RestController
class ProductController(
    private val productService: ProductService,
) {

    @PostMapping("/product/buy")
    fun buy(@RequestBody request: BuyRequest): BuyResponse =
        BuyResponse(productService.buy(request.toCommand()))

    @PostMapping("/product/buy/cancel")
    fun cancel(@RequestBody request: BuyCancelRequest): BuyCancelResponse =
        BuyCancelResponse(productService.cancel(request.toCommand()))
}
