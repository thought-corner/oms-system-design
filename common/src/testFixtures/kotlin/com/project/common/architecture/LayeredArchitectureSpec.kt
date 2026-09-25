package com.project.common.architecture

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import com.tngtech.archunit.library.Architectures.layeredArchitecture
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices
import io.kotest.core.spec.style.BehaviorSpec
import java.time.LocalDateTime
import java.time.ZoneId

abstract class LayeredArchitectureSpec(basePackage: String) : BehaviorSpec({

    val classes = ClassFileImporter()
        .withImportOption(ImportOption.DoNotIncludeTests())
        .importPackages(basePackage)

    Given("$basePackage — 최상위 패키지 = 레이어") {

        Then("의존 방향은 controller·messaging → service → repository/statemachine/client → domain 이다") {
            layeredArchitecture().consideringOnlyDependenciesInLayers()
                .withOptionalLayers(true)
                .layer("Controller").definedBy("..controller..")
                .layer("Messaging").definedBy("..messaging..")
                .layer("Service").definedBy("..service..")
                .layer("Repository").definedBy("..repository..")
                .layer("Domain").definedBy("..domain..")
                .layer("StateMachine").definedBy("..statemachine..")
                .layer("Client").definedBy("..client..")
                .layer("Config").definedBy("..config..")
                .layer("Init").definedBy("..init..")
                .whereLayer("Controller").mayNotBeAccessedByAnyLayer()
                .whereLayer("Messaging").mayNotBeAccessedByAnyLayer()
                .whereLayer("Service").mayOnlyBeAccessedByLayers("Controller", "Messaging")
                .whereLayer("Repository").mayOnlyBeAccessedByLayers("Service", "Init")
                .whereLayer("StateMachine").mayOnlyBeAccessedByLayers("Service")
                .whereLayer("Client").mayOnlyBeAccessedByLayers("Service", "Config")
                .whereLayer("Domain").mayOnlyBeAccessedByLayers("Service", "Repository", "StateMachine", "Init")
                .whereLayer("Config").mayNotBeAccessedByAnyLayer()
                .whereLayer("Init").mayNotBeAccessedByAnyLayer()
                .check(classes)
        }

        Then("패키지 사이에 순환 의존이 없다") {
            slices().matching("$basePackage.(*)..").should().beFreeOfCycles().check(classes)
        }

        Then("원격 호출은 client 밖에서 하지 않는다 (config는 배선만 한다)") {
            LayeredArchitectureRules.httpClientOnlyInClient.check(classes)
        }

        Then("메시지 발행(KafkaTemplate·프로듀서)은 client 밖에서 하지 않는다 (config는 배선만 한다)") {
            LayeredArchitectureRules.kafkaPublishingOnlyInClient.check(classes)
        }
    }

    Given("$basePackage — 어노테이션이 있어야 할 자리") {

        Then("`@Lock`은 repository 에만 붙는다") {
            LayeredArchitectureRules.lockOnlyInRepository.check(classes)
        }

        Then("`@Transactional`은 service 에만 붙는다") {
            LayeredArchitectureRules.transactionalMethodsOnlyInService.check(classes)
        }

        Then("클래스 레벨 `@Transactional`도 service 에만 붙는다") {
            LayeredArchitectureRules.transactionalClassesOnlyInService.check(classes)
        }

        Then("`@Entity`는 domain 에만 붙는다") {
            LayeredArchitectureRules.entityOnlyInDomain.check(classes)
        }

        Then("`@Service`는 service 에만 붙는다") {
            LayeredArchitectureRules.serviceOnlyInService.check(classes)
        }

        Then("`@RestController`는 controller 에만 붙는다") {
            LayeredArchitectureRules.restControllerOnlyInController.check(classes)
        }

        Then("`@KafkaListener`는 messaging 에만 붙는다") {
            LayeredArchitectureRules.kafkaListenerMethodsOnlyInMessaging.check(classes)
        }

        Then("클래스 레벨 `@KafkaListener`도 messaging 에만 붙는다") {
            LayeredArchitectureRules.kafkaListenerClassesOnlyInMessaging.check(classes)
        }
    }

    Given("$basePackage — 컴파일이 못 잡는 규칙") {

        Then("exception은 어느 레이어에도 의존하지 않는다") {
            noClasses().that().resideInAPackage("..exception..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("..controller..", "..messaging..", "..service..", "..repository..", "..domain..", "..statemachine..", "..client..")
                .allowEmptyShould(true)
                .check(classes)
        }

        Then("HttpStatus는 exception 밖에서 쓰지 않는다") {
            LayeredArchitectureRules.httpStatusOnlyInException.check(classes)
        }

        Then("LocalDateTime.now()를 직접 부르지 않는다 (시각은 Clock 빈에서)") {
            noClasses().should()
                .callMethod(LocalDateTime::class.java, "now")
                .orShould().callMethod(LocalDateTime::class.java, "now", ZoneId::class.java)
                .allowEmptyShould(true)
                .check(classes)
        }

        Then("맨 RuntimeException은 만들지 않는다 (BusinessException + ErrorCode로)") {
            noClasses().should()
                .callConstructor(RuntimeException::class.java, String::class.java)
                .orShould().callConstructor(RuntimeException::class.java)
                .orShould().callConstructor(RuntimeException::class.java, String::class.java, Throwable::class.java)
                .orShould().callConstructor(RuntimeException::class.java, Throwable::class.java)
                .allowEmptyShould(true)
                .check(classes)
        }
    }
})
