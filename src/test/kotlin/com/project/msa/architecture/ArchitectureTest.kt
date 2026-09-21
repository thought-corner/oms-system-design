package com.project.msa.architecture

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import com.tngtech.archunit.library.Architectures.layeredArchitecture
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices
import com.project.msa.domain.Order
import com.project.msa.domain.OrderStatus
import com.project.msa.service.OrderService
import io.kotest.core.spec.style.BehaviorSpec
import jakarta.persistence.Entity
import org.springframework.data.jpa.repository.Lock
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDateTime

class ArchitectureTest : BehaviorSpec({

    val classes = ClassFileImporter()
        .withImportOption(ImportOption.DoNotIncludeTests())
        .importPackages("com.project.msa")

    Given("최상위 패키지 = 레이어") {

        Then("의존 방향은 controller → service → repository/statemachine → domain 이고 init 은 repository·domain 만 본다") {
            layeredArchitecture().consideringOnlyDependenciesInLayers()
                .layer("Controller").definedBy("..controller..")
                .layer("Service").definedBy("..service..")
                .layer("Repository").definedBy("..repository..")
                .layer("Domain").definedBy("..domain..")
                .layer("StateMachine").definedBy("..statemachine..")
                .layer("Init").definedBy("..init..")
                .whereLayer("Controller").mayNotBeAccessedByAnyLayer()
                .whereLayer("Service").mayOnlyBeAccessedByLayers("Controller")
                .whereLayer("Repository").mayOnlyBeAccessedByLayers("Service", "Init")
                .whereLayer("StateMachine").mayOnlyBeAccessedByLayers("Service")
                .whereLayer("Domain").mayOnlyBeAccessedByLayers("Service", "Repository", "StateMachine", "Init")
                .whereLayer("Init").mayNotBeAccessedByAnyLayer()
                .check(classes)
        }

        Then("패키지 사이에 순환 의존이 없다") {
            slices().matching("com.project.msa.(*)..").should().beFreeOfCycles().check(classes)
        }
    }

    Given("exception 패키지") {

        Then("어느 레이어에도 의존하지 않는다") {
            noClasses().that().resideInAPackage("..exception..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("..controller..", "..service..", "..repository..", "..domain..", "..statemachine..")
                .check(classes)
        }

        Then("HttpStatus 는 exception 밖에서 쓰지 않는다") {
            noClasses().that().resideOutsideOfPackage("..exception..")
                .should().dependOnClassesThat().belongToAnyOf(HttpStatus::class.java)
                .check(classes)
        }

        Then("맨 RuntimeException 은 exception 밖에서 만들지 않는다") {
            noClasses().that().resideOutsideOfPackage("..exception..")
                .should().callConstructor(RuntimeException::class.java, String::class.java)
                .orShould().callConstructor(RuntimeException::class.java)
                .check(classes)
        }
    }

    Given("애너테이션의 자리") {

        Then("@Lock 메서드는 repository 에만 있다") {
            methods().that().areAnnotatedWith(Lock::class.java)
                .should().beDeclaredInClassesThat().resideInAPackage("..repository..")
                .check(classes)
        }

        Then("@Transactional 메서드는 service 에만 있다") {
            methods().that().areAnnotatedWith(Transactional::class.java)
                .should().beDeclaredInClassesThat().resideInAPackage("..service..")
                .check(classes)
        }

        Then("@Transactional 클래스도 service 에만 있다") {
            classes().that().areAnnotatedWith(Transactional::class.java)
                .should().resideInAPackage("..service..")
                .allowEmptyShould(true)
                .check(classes)
        }

        Then("@Entity 는 domain 에 있다") {
            classes().that().areAnnotatedWith(Entity::class.java)
                .should().resideInAPackage("..domain..")
                .check(classes)
        }

        Then("@Service 는 service 에 있다") {
            classes().that().areAnnotatedWith(Service::class.java)
                .should().resideInAPackage("..service..")
                .check(classes)
        }

        Then("@RestController 는 controller 에 있다") {
            classes().that().areAnnotatedWith(RestController::class.java)
                .should().resideInAPackage("..controller..")
                .check(classes)
        }
    }

    Given("상태 전이") {

        Then("Order.transitionTo 는 OrderService 만 부른다 — 전이 판정은 OrderStateMachine 을 거친다") {
            noClasses().that().doNotBelongToAnyOf(OrderService::class.java)
                .should().callMethod(Order::class.java, "transitionTo", OrderStatus::class.java)
                .check(classes)
        }
    }

    Given("시각") {

        Then("LocalDateTime.now() 는 어디서도 부르지 않는다 — Clock 을 주입받는다") {
            noClasses().should().callMethod(LocalDateTime::class.java, "now").check(classes)
        }
    }
})
