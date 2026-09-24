package com.project.order.client

import com.project.common.exception.BusinessException
import com.project.order.exception.ProductErrorCode
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import java.time.Duration

private val NO_BACKOFF: Duration = Duration.ZERO

class RemoteCallerTest : BehaviorSpec({

    Given("한 번에 성공하는 호출") {
        val caller = RemoteCaller(NO_BACKOFF)
        var attempts = 0

        When("부르면") {
            val result = caller.call("op", maxAttempts = 3) {
                attempts += 1
                "ok"
            }

            Then("결과를 돌려주고 한 번만 시도한다") {
                result shouldBe "ok"
                attempts shouldBe 1
            }
        }
    }

    Given("두 번 실패하고 세 번째에 성공하는 호출") {
        val caller = RemoteCaller(NO_BACKOFF)
        var attempts = 0

        When("부르면") {
            val result = caller.call("op", maxAttempts = 3) {
                attempts += 1
                if (attempts < 3) throw IllegalStateException("connection reset")
                "ok"
            }

            Then("재시도해서 성공하고 세 번 시도한다") {
                result shouldBe "ok"
                attempts shouldBe 3
            }
        }
    }

    Given("계속 실패하는 호출") {
        val caller = RemoteCaller(NO_BACKOFF)
        var attempts = 0

        When("부르면") {
            val exception = shouldThrow<IllegalStateException> {
                caller.call("op", maxAttempts = 3) {
                    attempts += 1
                    throw IllegalStateException("connection reset")
                }
            }

            Then("시도 횟수를 다 쓰고 마지막 실패를 올린다") {
                attempts shouldBe 3
                exception.message shouldBe "connection reset"
            }
        }
    }

    Given("비즈니스 실패를 내는 호출") {
        val caller = RemoteCaller(NO_BACKOFF)
        var attempts = 0

        When("부르면") {
            val exception = shouldThrow<BusinessException> {
                caller.call("op", maxAttempts = 3) {
                    attempts += 1
                    throw BusinessException(ProductErrorCode.INSUFFICIENT_STOCK)
                }
            }

            Then("재고 부족은 다시 불러도 결과가 같으므로 재시도하지 않는다") {
                attempts shouldBe 1
                exception.errorCode shouldBe ProductErrorCode.INSUFFICIENT_STOCK
            }
        }
    }

    Given("maxAttempts가 1인 호출") {
        val caller = RemoteCaller(NO_BACKOFF)
        var attempts = 0

        When("실패하면") {
            shouldThrow<IllegalStateException> {
                caller.call("op", maxAttempts = 1) {
                    attempts += 1
                    throw IllegalStateException("boom")
                }
            }

            Then("재시도 없이 한 번만 시도한다") {
                attempts shouldBe 1
            }
        }
    }
})
