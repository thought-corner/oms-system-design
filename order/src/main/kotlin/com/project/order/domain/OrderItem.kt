package com.project.order.domain

import com.project.common.exception.BusinessException
import com.project.order.exception.OrderErrorCode
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
        if (quantity !in 1..MAX_QUANTITY) {
            throw BusinessException(OrderErrorCode.INVALID_ORDER, "productId=$productId, quantity=$quantity")
        }
    }

    companion object {
        const val MAX_QUANTITY: Long = 1_000
    }
}
