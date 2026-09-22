package com.project.order.client

import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.web.client.RestClient
import tools.jackson.databind.ObjectMapper
import java.time.Duration

class ApiClientTestSupport {

    private val builder: RestClient.Builder = RestClient.builder().baseUrl("http://remote")

    val server: MockRestServiceServer = MockRestServiceServer.bindTo(builder).build()
    val objectMapper: ObjectMapper = ObjectMapper()
    val caller: RemoteCaller = RemoteCaller(Duration.ZERO)

    fun restClient(): RestClient = builder.build()
}
