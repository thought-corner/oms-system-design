package com.project.order.client

import com.project.common.exception.BusinessException
import com.project.order.client.dto.PayApiRequest
import com.project.order.client.dto.PayApiResponse
import com.project.order.client.dto.PayCancelApiRequest
import com.project.order.exception.PaymentErrorCode
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import tools.jackson.databind.ObjectMapper

class PaymentApiClient(
    private val restClient: RestClient,
    private val policy: RemoteCallPolicy,
    private val remoteCaller: RemoteCaller,
    objectMapper: ObjectMapper,
) {

    private val translator = RemoteErrorTranslator(
        objectMapper,
        PaymentErrorCode.entries.associateBy { it.code },
        PaymentErrorCode.PAYMENT_FAILED,
    )

    fun pay(request: PayApiRequest): PayApiResponse =
        try {
            remoteCaller.call("payment.pay", policy.maxAttempts) {
                translator.translating {
                    checkNotNull(
                        restClient.post().uri("/payment").body(request).retrieve().body(PayApiResponse::class.java),
                    )
                }
            }
        } catch (e: RestClientException) {
            throw BusinessException(PaymentErrorCode.PAYMENT_FAILED, "sagaId=${request.sagaId}, cause=${e.javaClass.simpleName}")
        }

    fun cancel(request: PayCancelApiRequest, maxAttempts: Int) {
        remoteCaller.call("payment.cancel", maxAttempts) {
            restClient.post().uri("/payment/cancel").body(request).retrieve().toBodilessEntity()
        }
    }
}
