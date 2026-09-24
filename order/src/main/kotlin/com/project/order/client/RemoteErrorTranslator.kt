package com.project.order.client

import com.project.common.exception.BusinessException
import com.project.common.exception.ErrorCode
import com.project.order.client.dto.RemoteErrorBody
import org.springframework.web.client.RestClientResponseException
import tools.jackson.databind.ObjectMapper

class RemoteErrorTranslator(
    private val objectMapper: ObjectMapper,
    private val codes: Map<String, ErrorCode>,
    private val fallback: ErrorCode,
) {

    fun <T> translating(block: () -> T): T =
        try {
            block()
        } catch (e: RestClientResponseException) {
            if (e.statusCode.is4xxClientError) throw translate(e)
            throw e
        }

    fun translate(e: RestClientResponseException): BusinessException {
        val code = runCatching {
            objectMapper.readValue(e.responseBodyAsString, RemoteErrorBody::class.java).code
        }.getOrNull()

        return BusinessException(codes[code] ?: fallback, "remoteStatus=${e.statusCode.value()}, remoteCode=$code")
    }
}
