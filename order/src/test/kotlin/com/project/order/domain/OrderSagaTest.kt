package com.project.order.domain

import com.project.order.fixture.OrderFixture
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.time.LocalDateTime

class OrderSagaTest : BehaviorSpec({

    val later: LocalDateTime = OrderFixture.FIXED_TIME.plusSeconds(5)

    Given("막 시작한 사가") {
        val saga = OrderFixture.saga()

        Then("RUNNING이고 첫 단계는 재고이며 되돌릴 것이 없다") {
            saga.status shouldBe SagaStatus.RUNNING
            saga.currentStep shouldBe SagaStep.STOCK
            saga.stockDone shouldBe false
            saga.pointDone shouldBe false
            saga.paymentDone shouldBe false
            saga.totalPrice shouldBe 0L
            saga.attempts shouldBe 0
            saga.lastError.shouldBeNull()
            saga.updatedAt shouldBe OrderFixture.FIXED_TIME
        }
    }

    Given("재고 차감이 끝난 사가") {
        val saga = OrderFixture.saga()
        saga.stockCompleted(OrderFixture.DEFAULT_TOTAL_PRICE, later)

        Then("총액을 기억하고 다음 단계가 포인트가 된다") {
            saga.stockDone shouldBe true
            saga.totalPrice shouldBe OrderFixture.DEFAULT_TOTAL_PRICE
            saga.currentStep shouldBe SagaStep.POINT
            saga.updatedAt shouldBe later
        }

        When("포인트와 결제까지 끝나면") {
            saga.pointCompleted(later)
            saga.paymentCompleted(later)

            Then("세 단계가 모두 성공으로 표시된다") {
                saga.pointDone shouldBe true
                saga.paymentDone shouldBe true
                saga.currentStep shouldBe SagaStep.PAYMENT
            }
        }
    }

    Given("상태를 바꿀 사가") {
        val saga = OrderFixture.saga()

        When("전이를 적용하면") {
            saga.transitionTo(SagaStatus.COMPENSATING, later)

            Then("상태와 갱신 시각이 함께 바뀐다") {
                saga.status shouldBe SagaStatus.COMPENSATING
                saga.updatedAt shouldBe later
            }
        }
    }

    Given("재고와 포인트까지 성공한 사가") {
        val saga = OrderFixture.saga()
        saga.stockCompleted(OrderFixture.DEFAULT_TOTAL_PRICE, later)
        saga.pointCompleted(later)

        When("보상이 끝나 COMPENSATED가 되어도") {
            saga.transitionTo(SagaStatus.COMPENSATING, later)
            saga.transitionTo(SagaStatus.COMPENSATED, later)

            Then("성공 단계 표시는 남는다 — 무엇을 되돌렸는지 관측할 수 있어야 한다") {
                saga.stockDone shouldBe true
                saga.pointDone shouldBe true
                saga.paymentDone shouldBe false
            }
        }
    }

    Given("보상이 실패한 사가") {
        val saga = OrderFixture.saga()

        When("오류를 기록하고 시도 횟수를 세면") {
            saga.recordError("product down")
            saga.countAttempt()
            saga.countAttempt()

            Then("마지막 오류와 시도 횟수가 남는다") {
                saga.lastError shouldBe "product down"
                saga.attempts shouldBe 2
            }
        }

        When("아주 긴 오류를 기록하면") {
            saga.recordError("x".repeat(500))

            Then("컬럼 길이에 맞춰 잘린다") {
                saga.lastError?.length shouldBe OrderSaga.MAX_ERROR_LENGTH
            }
        }

        When("오류가 null이면") {
            saga.recordError(null)

            Then("지워진다") {
                saga.lastError.shouldBeNull()
            }
        }
    }

    Given("재발행을 두 번 겪은 재고 단계 사가") {
        val saga = OrderFixture.sagaAt(SagaStep.STOCK, attempts = 2)

        Then("재고 단계만 기다리고 결제 전이라 실패할 수 있다") {
            saga.isAt(SagaStep.STOCK) shouldBe true
            saga.isAt(SagaStep.POINT) shouldBe false
            saga.canFailAt(SagaStep.STOCK) shouldBe true
            saga.idempotencyKey shouldBe "key-${OrderFixture.DEFAULT_SAGA_ID}"
        }

        When("응답이 와서 단계가 전진하면") {
            saga.stockCompleted(OrderFixture.DEFAULT_TOTAL_PRICE, later)

            Then("진전이므로 시도 횟수가 비워진다") {
                saga.attempts shouldBe 0
            }
        }
    }

    Given("결제까지 승인된 사가") {
        val saga = OrderFixture.sagaAt(SagaStep.PAYMENT, paymentDone = true)

        Then("결제 단계에 있지만 더는 실패로 되돌릴 수 없다") {
            saga.isAt(SagaStep.PAYMENT) shouldBe true
            saga.canFailAt(SagaStep.PAYMENT) shouldBe false
        }
    }

    Given("정방향 실패를 받은 사가") {
        val saga = OrderFixture.sagaAt(SagaStep.POINT)

        When("실패 코드를 두 번 기록하면") {
            val first = saga.recordFailure("INSUFFICIENT_POINT")
            val second = saga.recordFailure("POINT_NOT_FOUND")

            Then("첫 코드만 남는다") {
                first shouldBe true
                second shouldBe false
                saga.failureCode shouldBe "INSUFFICIENT_POINT"
            }
        }
    }

    Given("보상을 시작한 사가") {
        val saga = OrderFixture.compensatingSaga()

        Then("세 곳 모두 되돌릴 대상이고 결제 → 포인트 → 재고 순이다") {
            saga.allCanceled shouldBe false
            saga.pendingCancels shouldBe listOf(SagaStep.PAYMENT, SagaStep.POINT, SagaStep.STOCK)
        }

        When("포인트 보상 응답이 두 번 오면") {
            saga.countAttempt()
            val first = saga.canceled(SagaStep.POINT, later)
            val second = saga.canceled(SagaStep.POINT, later.plusSeconds(1))

            Then("한 번만 기록되고 두 번째는 갱신 시각을 바꾸지 않는다") {
                first shouldBe true
                second shouldBe false
                saga.attempts shouldBe 0
                saga.updatedAt shouldBe later
                saga.pendingCancels shouldBe listOf(SagaStep.PAYMENT, SagaStep.STOCK)
            }
        }

        When("나머지 두 보상 응답이 오면") {
            saga.canceled(SagaStep.PAYMENT, later)
            saga.canceled(SagaStep.STOCK, later)

            Then("셋 다 켜진다") {
                saga.stockCanceled shouldBe true
                saga.paymentCanceled shouldBe true
                saga.allCanceled shouldBe true
                saga.pendingCancels shouldBe emptyList()
            }
        }
    }
})
