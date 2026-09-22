package com.project.point.domain

import com.project.common.exception.BusinessException
import com.project.point.exception.PointErrorCode
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table

@Entity
@Table(name = "points")
class Point(
    @Column(unique = true)
    val userId: Long,
    amount: Long,
) {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set

    var amount: Long = amount
        protected set

    fun use(amount: Long) {
        require(amount >= 0) { "amount=$amount" }
        if (this.amount < amount) {
            throw BusinessException(PointErrorCode.INSUFFICIENT_POINT, "userId=$userId, requested=$amount")
        }

        this.amount -= amount
    }

    fun refund(amount: Long) {
        require(amount >= 0) { "amount=$amount" }

        this.amount += amount
    }
}
