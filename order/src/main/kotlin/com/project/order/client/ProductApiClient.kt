package com.project.order.client

import com.project.order.client.dto.BuyApiRequest
import com.project.order.client.dto.BuyApiResponse
import com.project.order.client.dto.BuyCancelApiRequest
import com.project.order.client.dto.BuyCancelApiResponse
import com.project.order.exception.ProductErrorCode
import org.springframework.web.client.RestClient
import tools.jackson.databind.ObjectMapper

class ProductApiClient(
    private val restClient: RestClient,
    private val policy: RemoteCallPolicy,
    private val remoteCaller: RemoteCaller,
    objectMapper: ObjectMapper,
) {

    private val translator = RemoteErrorTranslator(
        objectMapper,
        ProductErrorCode.entries.associateBy { it.code },
        ProductErrorCode.PRODUCT_NOT_FOUND,
    )

    fun buy(request: BuyApiRequest): Long =
        remoteCaller.call("product.buy", policy.maxAttempts) {
            translator.translating {
                checkNotNull(
                    restClient.post().uri("/product/buy").body(request).retrieve().body(BuyApiResponse::class.java),
                ).totalPrice
            }
        }

    fun cancel(request: BuyCancelApiRequest, maxAttempts: Int): Long =
        remoteCaller.call("product.cancel", maxAttempts) {
            checkNotNull(
                restClient.post().uri("/product/buy/cancel").body(request).retrieve()
                    .body(BuyCancelApiResponse::class.java),
            ).restoredPrice
        }
}
