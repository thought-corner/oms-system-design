package com.project.product.domain

import com.project.common.exception.BusinessException
import com.project.product.exception.ProductErrorCode
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table

@Entity
@Table(name = "products")
class Product(
    @Id
    val id: Long,
    quantity: Long,
    val price: Long,
) {

    var quantity: Long = quantity
        protected set

    fun calculatePrice(quantity: Long): Long = Math.multiplyExact(price, quantity)

    fun buy(quantity: Long) {
        require(quantity > 0) { "quantity=$quantity" }
        if (this.quantity < quantity) {
            throw BusinessException(ProductErrorCode.INSUFFICIENT_STOCK, "productId=$id, stock=${this.quantity}, requested=$quantity")
        }

        this.quantity -= quantity
    }

    fun restore(quantity: Long) {
        require(quantity > 0) { "quantity=$quantity" }

        this.quantity += quantity
    }
}
