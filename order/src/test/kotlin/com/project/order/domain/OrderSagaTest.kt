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
})
