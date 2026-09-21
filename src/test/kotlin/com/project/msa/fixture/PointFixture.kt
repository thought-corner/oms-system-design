package com.project.msa.fixture

import com.project.msa.domain.Point

object PointFixture {

    fun point(
        userId: Long = 1L,
        amount: Long = 10000L,
        id: Long? = 1L,
    ): Point = Point(userId = userId, amount = amount).let { if (id == null) it else it.withId(id) }
}
