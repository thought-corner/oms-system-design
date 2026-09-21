package com.project.msa.domain

import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table

@Entity
@Table(name = "orders")
class Order(
    val userId: Long,
) {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set

    @Enumerated(EnumType.STRING)
    var status: OrderStatus = OrderStatus.CREATED
        protected set

    val isCompleted: Boolean
        get() = status == OrderStatus.COMPLETED

    fun transitionTo(next: OrderStatus) {
        status = next
    }
}
