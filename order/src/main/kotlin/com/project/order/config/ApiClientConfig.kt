package com.project.order.config

import com.project.order.client.PaymentApiClient
import com.project.order.client.PointApiClient
import com.project.order.client.ProductApiClient
import com.project.order.client.RemoteCallPolicy
import com.project.order.client.RemoteCaller
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.web.client.RestClient
import tools.jackson.databind.ObjectMapper

@Configuration
class ApiClientConfig(
    private val properties: ServiceProperties,
    private val objectMapper: ObjectMapper,
) {

    @Bean
    fun remoteCaller(): RemoteCaller = RemoteCaller(RemoteCallPolicy.BACKOFF)

    @Bean
    fun productApiClient(remoteCaller: RemoteCaller): ProductApiClient =
        ProductApiClient(restClient(properties.product, RemoteCallPolicy.LOCAL_WRITE), RemoteCallPolicy.LOCAL_WRITE, remoteCaller, objectMapper)

    @Bean
    fun pointApiClient(remoteCaller: RemoteCaller): PointApiClient =
        PointApiClient(restClient(properties.point, RemoteCallPolicy.LOCAL_WRITE), RemoteCallPolicy.LOCAL_WRITE, remoteCaller, objectMapper)

    @Bean
    fun paymentApiClient(remoteCaller: RemoteCaller): PaymentApiClient =
        PaymentApiClient(restClient(properties.payment, RemoteCallPolicy.EXTERNAL_APPROVAL), RemoteCallPolicy.EXTERNAL_APPROVAL, remoteCaller, objectMapper)

    private fun restClient(baseUrl: String, policy: RemoteCallPolicy): RestClient {
        val factory = SimpleClientHttpRequestFactory().apply {
            setConnectTimeout(policy.timeout)
            setReadTimeout(policy.timeout)
        }

        return RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build()
    }
}
