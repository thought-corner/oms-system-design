package com.project.msa.domain

import com.project.msa.exception.BusinessException
import com.project.msa.exception.OrderErrorCode
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table

@Entity
@Table(name = "order_items")
class OrderItem(
    val orderId: Long,
    val productId: Long,
    val quantity: Long,
) {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set

    init {
        if (quantity <= 0) {
            throw BusinessException(OrderErrorCode.INVALID_ORDER, "productId=$productId, quantity=$quantity")
        }
    }
}
