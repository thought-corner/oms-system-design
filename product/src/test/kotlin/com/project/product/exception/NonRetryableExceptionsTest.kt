package com.project.product.exception

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class NonRetryableExceptionsTest : BehaviorSpec({

    Given("다시 해도 결과가 같은 예외 이름") {
        val names = listOf(
            "com.google.protobuf.InvalidProtocolBufferException",
            "com.google.protobuf.InvalidProtocolBufferException\$InvalidWireTypeException",
            "java.lang.ArithmeticException",
            "java.lang.IllegalArgumentException",
            "java.lang.NumberFormatException",
        )

        When("재시도 불가인지 물으면") {

            Then("하위 클래스까지 모두 재시도 불가다") {
                names.map { NonRetryableExceptions.includes(it) } shouldBe names.map { true }
            }
        }
    }

    Given("일시 장애일 수 있는 예외 이름") {
        val names = listOf(
            "java.lang.IllegalStateException",
            "tools.jackson.core.JacksonException",
            "org.springframework.dao.CannotAcquireLockException",
            "org.springframework.kafka.listener.ListenerExecutionFailedException",
            "java.lang.String",
        )

        When("재시도 불가인지 물으면") {

            Then("재시도할 수 있는 쪽이다") {
                names.map { NonRetryableExceptions.includes(it) } shouldBe names.map { false }
            }
        }
    }

    Given("원인 예외 이름이 없거나 이 JVM 에서 읽을 수 없는 레코드") {

        When("재시도 불가인지 물으면") {

            Then("없으면 재시도 쪽이고, 읽을 수 없으면 목록의 이름과 같을 때만 재시도 불가다") {
                NonRetryableExceptions.includes(null) shouldBe false
                NonRetryableExceptions.includes("com.example.UnknownException") shouldBe false
            }
        }
    }
})
