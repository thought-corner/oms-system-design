package com.project.order.client

import com.project.order.client.dto.UseApiRequest
import com.project.order.client.dto.UseCancelApiRequest
import com.project.order.client.dto.UseCancelApiResponse
import com.project.order.exception.PointErrorCode
import org.springframework.web.client.RestClient
import tools.jackson.databind.ObjectMapper

class PointApiClient(
    private val restClient: RestClient,
    private val policy: RemoteCallPolicy,
    private val remoteCaller: RemoteCaller,
    objectMapper: ObjectMapper,
) {

    private val translator = RemoteErrorTranslator(
        objectMapper,
        PointErrorCode.entries.associateBy { it.code },
        PointErrorCode.POINT_NOT_FOUND,
    )

    fun use(request: UseApiRequest) {
        remoteCaller.call("point.use", policy.maxAttempts) {
            translator.translating {
                restClient.post().uri("/point/use").body(request).retrieve().toBodilessEntity()
            }
        }
    }

    fun cancel(request: UseCancelApiRequest, maxAttempts: Int): Long =
        remoteCaller.call("point.cancel", maxAttempts) {
            checkNotNull(
                restClient.post().uri("/point/use/cancel").body(request).retrieve()
                    .body(UseCancelApiResponse::class.java),
            ).refundedAmount
        }
}
