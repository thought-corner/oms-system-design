package com.project.common.architecture

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import com.tngtech.archunit.library.Architectures.layeredArchitecture
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices
import io.kotest.core.spec.style.BehaviorSpec
import jakarta.persistence.Entity
import org.springframework.data.jpa.repository.Lock
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDateTime

abstract class LayeredArchitectureSpec(basePackage: String) : BehaviorSpec({

    val classes = ClassFileImporter()
        .withImportOption(ImportOption.DoNotIncludeTests())
        .importPackages(basePackage)

    Given("$basePackage — 최상위 패키지 = 레이어") {

        Then("의존 방향은 controller → service → repository/statemachine/client → domain 이다") {
            layeredArchitecture().consideringOnlyDependenciesInLayers()
                .withOptionalLayers(true)
                .layer("Controller").definedBy("..controller..")
                .layer("Service").definedBy("..service..")
                .layer("Repository").definedBy("..repository..")
                .layer("Domain").definedBy("..domain..")
                .layer("StateMachine").definedBy("..statemachine..")
                .layer("Client").definedBy("..client..")
                .layer("Config").definedBy("..config..")
                .layer("Init").definedBy("..init..")
                .whereLayer("Controller").mayNotBeAccessedByAnyLayer()
                .whereLayer("Service").mayOnlyBeAccessedByLayers("Controller")
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
            noClasses().that().resideOutsideOfPackage("..client..")
                .and().resideOutsideOfPackage("..config..")
                .should().dependOnClassesThat().resideInAPackage("org.springframework.web.client..")
                .allowEmptyShould(true)
                .check(classes)
        }
    }

    Given("$basePackage — 어노테이션이 있어야 할 자리") {

        Then("`@Lock`은 repository 에만 붙는다") {
            methods().that().areAnnotatedWith(Lock::class.java)
                .should().beDeclaredInClassesThat().resideInAPackage("..repository..")
                .allowEmptyShould(true)
                .check(classes)
        }

        Then("`@Transactional`은 service 에만 붙는다") {
            methods().that().areAnnotatedWith(Transactional::class.java)
                .or().areAnnotatedWith(JAKARTA_TRANSACTIONAL)
                .should().beDeclaredInClassesThat().resideInAPackage("..service..")
                .allowEmptyShould(true)
                .check(classes)
        }

        Then("클래스 레벨 `@Transactional`도 service 에만 붙는다") {
            classes().that().areAnnotatedWith(Transactional::class.java)
                .or().areAnnotatedWith(JAKARTA_TRANSACTIONAL)
                .should().resideInAPackage("..service..")
                .allowEmptyShould(true)
                .check(classes)
        }

        Then("`@Entity`는 domain 에만 붙는다") {
            classes().that().areAnnotatedWith(Entity::class.java)
                .should().resideInAPackage("..domain..")
                .allowEmptyShould(true)
                .check(classes)
        }

        Then("`@Service`는 service 에만 붙는다") {
            classes().that().areAnnotatedWith(Service::class.java)
                .should().resideInAPackage("..service..")
                .allowEmptyShould(true)
                .check(classes)
        }

        Then("`@RestController`는 controller 에만 붙는다") {
            classes().that().areAnnotatedWith(RestController::class.java)
                .should().resideInAPackage("..controller..")
                .allowEmptyShould(true)
                .check(classes)
        }
    }

    Given("$basePackage — 컴파일이 못 잡는 규칙") {

        Then("exception은 어느 레이어에도 의존하지 않는다") {
            noClasses().that().resideInAPackage("..exception..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("..controller..", "..service..", "..repository..", "..domain..", "..statemachine..", "..client..")
                .allowEmptyShould(true)
                .check(classes)
        }

        Then("HttpStatus는 exception 밖에서 쓰지 않는다") {
            noClasses().that().resideOutsideOfPackage("..exception..")
                .should().dependOnClassesThat().belongToAnyOf(HttpStatus::class.java)
                .allowEmptyShould(true)
                .check(classes)
        }

        Then("LocalDateTime.now()를 직접 부르지 않는다 (시각은 Clock 빈에서)") {
            noClasses().should()
                .callMethod(LocalDateTime::class.java, "now")
                .orShould().callMethod(LocalDateTime::class.java, "now", java.time.ZoneId::class.java)
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

private const val JAKARTA_TRANSACTIONAL = "jakarta.transaction.Transactional"
