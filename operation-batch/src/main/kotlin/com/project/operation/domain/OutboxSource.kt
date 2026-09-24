package com.project.operation.domain

enum class OutboxSource(val schema: String) {
    ORDER("order"),
    PRODUCT("product"),
    POINT("point"),
    PAYMENT("payment"),
    ;

    val table: String
        get() = "`$schema`.`outbox`"
}
