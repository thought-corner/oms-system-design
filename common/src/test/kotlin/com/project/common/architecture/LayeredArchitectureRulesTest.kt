package com.project.common.architecture

import com.tngtech.archunit.core.importer.ClassFileImporter
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class LayeredArchitectureRulesTest : BehaviorSpec({

    val misplaced = ClassFileImporter().importPackages("com.project.common.architecture.violation")

    Given("어느 레이어 패키지에도 속하지 않는 곳에 둔 위반 클래스") {
        listOf(
            "원격 호출은 client 에서만" to LayeredArchitectureRules.httpClientOnlyInClient,
            "메시지 발행은 client 에서만" to LayeredArchitectureRules.kafkaPublishingOnlyInClient,
            "`@Lock` 은 repository 에만" to LayeredArchitectureRules.lockOnlyInRepository,
            "`@Transactional` 메서드는 service 에만" to LayeredArchitectureRules.transactionalMethodsOnlyInService,
            "`@Transactional` 클래스는 service 에만" to LayeredArchitectureRules.transactionalClassesOnlyInService,
            "`@Entity` 는 domain 에만" to LayeredArchitectureRules.entityOnlyInDomain,
            "`@Service` 는 service 에만" to LayeredArchitectureRules.serviceOnlyInService,
            "`@RestController` 는 controller 에만" to LayeredArchitectureRules.restControllerOnlyInController,
            "`@KafkaListener` 메서드는 messaging 에만" to LayeredArchitectureRules.kafkaListenerMethodsOnlyInMessaging,
            "`@KafkaListener` 클래스는 messaging 에만" to LayeredArchitectureRules.kafkaListenerClassesOnlyInMessaging,
            "HttpStatus 는 exception 에서만" to LayeredArchitectureRules.httpStatusOnlyInException,
        ).forEach { (rule, archRule) ->
            When("'$rule' 규칙으로 검사하면") {
                val result = archRule.evaluate(misplaced)

                Then("이름으로 적은 타입이 실제 클래스와 맞아 위반을 잡는다") {
                    result.hasViolation() shouldBe true
                }
            }
        }
    }
})
