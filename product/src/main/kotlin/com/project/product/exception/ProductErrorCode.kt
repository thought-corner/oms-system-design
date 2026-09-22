package com.project.product.exception

import com.project.common.exception.ErrorCode
import org.springframework.http.HttpStatus

enum class ProductErrorCode(
    override val status: HttpStatus,
    override val message: String,
) : ErrorCode {
    PRODUCT_NOT_FOUND(HttpStatus.NOT_FOUND, "상품이 존재하지 않습니다."),
    INSUFFICIENT_STOCK(HttpStatus.CONFLICT, "재고가 부족합니다."),
    ;

    override val code: String = name
}
