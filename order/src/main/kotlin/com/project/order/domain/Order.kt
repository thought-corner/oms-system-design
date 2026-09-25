package com.project.order.domain

import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDateTime

@Entity
@Table(name = "orders")
class Order(
    val userId: Long,
    val createdAt: LocalDateTime,
) {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set

    @Enumerated(EnumType.STRING)
    var status: OrderStatus = OrderStatus.CREATED
        protected set

    var updatedAt: LocalDateTime = createdAt
        protected set

    val isPlacing: Boolean
        get() = status == OrderStatus.PLACING

    fun transitionTo(next: OrderStatus, at: LocalDateTime) {
        status = next
        updatedAt = at
    }
}
