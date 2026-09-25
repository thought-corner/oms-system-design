package com.project.point.client

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.time.Duration

@Component
class DiscordAlertSender(
    private val actionWebhookUrl: String,
    private val incidentWebhookUrl: String,
    private val defectWebhookUrl: String,
    private val restClient: RestClient,
) : AlertSender {

    @Autowired
    constructor(
        @Value("\${alert.discord.action-webhook-url:}") actionWebhookUrl: String,
        @Value("\${alert.discord.incident-webhook-url:}") incidentWebhookUrl: String,
        @Value("\${alert.discord.defect-webhook-url:}") defectWebhookUrl: String,
    ) : this(actionWebhookUrl, incidentWebhookUrl, defectWebhookUrl, defaultRestClient())

    private val log = LoggerFactory.getLogger(javaClass)

    override fun send(alert: DeadLetterAlert) {
        val message = AlertMessageFormatter.format(alert)
        log.error(message)
        val webhookUrl = webhookUrlOf(alert)
        if (webhookUrl.isBlank()) {
            return
        }
        try {
            restClient.post()
                .uri(webhookUrl)
                .contentType(MediaType.APPLICATION_JSON)
                .body(mapOf("content" to message.take(CONTENT_LIMIT), "allowed_mentions" to mapOf("parse" to emptyList<String>())))
                .retrieve()
                .toBodilessEntity()
        } catch (e: RuntimeException) {
            log.error("Discord alert was not delivered. sagaId={}, orderId={}", alert.sagaId, alert.orderId, e)
        }
    }

    private fun webhookUrlOf(alert: DeadLetterAlert): String =
        when (alert.kind) {
            DeadLetterKind.POISON -> if (alert.failedReplyWritten) defectWebhookUrl else actionWebhookUrl
            DeadLetterKind.RETRY_EXHAUSTED -> incidentWebhookUrl
        }

    companion object {
        const val CONTENT_LIMIT = 2000
        private val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(2)
        private val READ_TIMEOUT: Duration = Duration.ofSeconds(3)

        private fun defaultRestClient(): RestClient =
            RestClient.builder()
                .requestFactory(
                    SimpleClientHttpRequestFactory().apply {
                        setConnectTimeout(CONNECT_TIMEOUT)
                        setReadTimeout(READ_TIMEOUT)
                    },
                )
                .build()
    }
}
