package com.project.operation.config

import com.project.operation.client.KafkaProducerPolicy
import org.springframework.boot.kafka.autoconfigure.DefaultKafkaProducerFactoryCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class KafkaConfig {

    @Bean
    fun producerPolicyCustomizer() = DefaultKafkaProducerFactoryCustomizer { factory ->
        factory.updateConfigs(KafkaProducerPolicy.configs())
    }
}
