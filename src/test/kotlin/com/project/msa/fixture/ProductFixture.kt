package com.project.msa.fixture

import com.project.msa.domain.Product

object ProductFixture {

    fun product(
        quantity: Long = 100L,
        price: Long = 100L,
        id: Long? = 1L,
    ): Product = Product(quantity = quantity, price = price).let { if (id == null) it else it.withId(id) }

    fun soldOut(id: Long = 2L, price: Long = 200L): Product = product(quantity = 0L, price = price, id = id)
}
