package com.project.common.architecture.violation

import jakarta.persistence.Entity
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.Lock
import org.springframework.http.HttpStatus
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.client.RestClient

@Entity
class MisplacedEntity

@Service
class MisplacedService

@RestController
class MisplacedController

@Transactional
class MisplacedTransactionalClass

class MisplacedTransactionalMethod {

    @Transactional
    fun run() = Unit
}

class MisplacedLock {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun find() = Unit
}

class MisplacedListenerMethod {

    @KafkaListener(topics = ["topic"])
    fun consume(value: String) = Unit
}

@KafkaListener(topics = ["topic"])
class MisplacedListenerClass

class MisplacedHttpStatus {

    fun status(): HttpStatus = HttpStatus.OK
}

class MisplacedPublisher(val kafkaTemplate: KafkaTemplate<String, String>)

class MisplacedHttpClient(val restClient: RestClient)
