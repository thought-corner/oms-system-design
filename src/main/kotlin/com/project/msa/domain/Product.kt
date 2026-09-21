package com.project.msa.domain

import com.project.msa.exception.BusinessException
import com.project.msa.exception.ProductErrorCode
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table

@Entity
@Table(name = "products")
class Product(
    quantity: Long,
    val price: Long,
) {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set

    var quantity: Long = quantity
        protected set

    fun calculatePrice(quantity: Long): Long = price * quantity

    fun buy(quantity: Long) {
        require(quantity > 0) { "quantity=$quantity" }
        if (this.quantity < quantity) {
            throw BusinessException(ProductErrorCode.INSUFFICIENT_STOCK, "productId=$id, stock=${this.quantity}, requested=$quantity")
        }

        this.quantity -= quantity
    }
}
