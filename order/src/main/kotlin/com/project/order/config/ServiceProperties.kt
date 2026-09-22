package com.project.order.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "service")
data class ServiceProperties(
    val product: String,
    val point: String,
    val payment: String,
)
