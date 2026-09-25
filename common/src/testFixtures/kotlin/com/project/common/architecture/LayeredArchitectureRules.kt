package com.project.common.architecture

import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses

object LayeredArchitectureRules {

    const val JPA_LOCK = "org.springframework.data.jpa.repository.Lock"
    const val JPA_ENTITY = "jakarta.persistence.Entity"
    const val SPRING_TRANSACTIONAL = "org.springframework.transaction.annotation.Transactional"
    const val JAKARTA_TRANSACTIONAL = "jakarta.transaction.Transactional"
    const val SPRING_SERVICE = "org.springframework.stereotype.Service"
    const val REST_CONTROLLER = "org.springframework.web.bind.annotation.RestController"
    const val KAFKA_LISTENER = "org.springframework.kafka.annotation.KafkaListener"
    const val HTTP_STATUS = "org.springframework.http.HttpStatus"
    const val HTTP_CLIENT_PACKAGE = "org.springframework.web.client.."
    const val KAFKA_CORE_PACKAGE = "org.springframework.kafka.core.."

    val httpClientOnlyInClient: ArchRule =
        noClasses().that().resideOutsideOfPackage("..client..")
            .and().resideOutsideOfPackage("..config..")
            .should().dependOnClassesThat().resideInAPackage(HTTP_CLIENT_PACKAGE)
            .allowEmptyShould(true)

    val kafkaPublishingOnlyInClient: ArchRule =
        noClasses().that().resideOutsideOfPackage("..client..")
            .and().resideOutsideOfPackage("..config..")
            .should().dependOnClassesThat().resideInAPackage(KAFKA_CORE_PACKAGE)
            .allowEmptyShould(true)

    val lockOnlyInRepository: ArchRule =
        methods().that().areAnnotatedWith(JPA_LOCK)
            .should().beDeclaredInClassesThat().resideInAPackage("..repository..")
            .allowEmptyShould(true)

    val transactionalMethodsOnlyInService: ArchRule =
        methods().that().areAnnotatedWith(SPRING_TRANSACTIONAL)
            .or().areAnnotatedWith(JAKARTA_TRANSACTIONAL)
            .should().beDeclaredInClassesThat().resideInAPackage("..service..")
            .allowEmptyShould(true)

    val transactionalClassesOnlyInService: ArchRule =
        classes().that().areAnnotatedWith(SPRING_TRANSACTIONAL)
            .or().areAnnotatedWith(JAKARTA_TRANSACTIONAL)
            .should().resideInAPackage("..service..")
            .allowEmptyShould(true)

    val entityOnlyInDomain: ArchRule =
        classes().that().areAnnotatedWith(JPA_ENTITY)
            .should().resideInAPackage("..domain..")
            .allowEmptyShould(true)

    val serviceOnlyInService: ArchRule =
        classes().that().areAnnotatedWith(SPRING_SERVICE)
            .should().resideInAPackage("..service..")
            .allowEmptyShould(true)

    val restControllerOnlyInController: ArchRule =
        classes().that().areAnnotatedWith(REST_CONTROLLER)
            .should().resideInAPackage("..controller..")
            .allowEmptyShould(true)

    val kafkaListenerMethodsOnlyInMessaging: ArchRule =
        methods().that().areAnnotatedWith(KAFKA_LISTENER)
            .should().beDeclaredInClassesThat().resideInAPackage("..messaging..")
            .allowEmptyShould(true)

    val kafkaListenerClassesOnlyInMessaging: ArchRule =
        classes().that().areAnnotatedWith(KAFKA_LISTENER)
            .should().resideInAPackage("..messaging..")
            .allowEmptyShould(true)

    val httpStatusOnlyInException: ArchRule =
        noClasses().that().resideOutsideOfPackage("..exception..")
            .should().dependOnClassesThat().haveFullyQualifiedName(HTTP_STATUS)
            .allowEmptyShould(true)
}
