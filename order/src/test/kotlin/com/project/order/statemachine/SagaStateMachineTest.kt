package com.project.order.statemachine

import com.project.common.exception.BusinessException
import com.project.order.domain.SagaEvent
import com.project.order.domain.SagaStatus
import com.project.order.exception.OrderErrorCode
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class SagaStateMachineTest : BehaviorSpec({

    Given("사가 상태 기계") {
        val stateMachine = SagaStateMachine()

        When("RUNNING에 COMPLETE를 보내면") {
            val next = stateMachine.transition("saga-1", SagaStatus.RUNNING, SagaEvent.COMPLETE)

            Then("SUCCEEDED로 간다") {
                next shouldBe SagaStatus.SUCCEEDED
            }
        }

        When("RUNNING에 COMPENSATE를 보내면") {
            val next = stateMachine.transition("saga-1", SagaStatus.RUNNING, SagaEvent.COMPENSATE)

            Then("COMPENSATING으로 간다") {
                next shouldBe SagaStatus.COMPENSATING
            }
        }

        When("COMPENSATING에 COMPENSATE를 다시 보내면") {
            val next = stateMachine.transition("saga-1", SagaStatus.COMPENSATING, SagaEvent.COMPENSATE)

            Then("거부하지 않고 COMPENSATING에 머문다 — 워커가 멈춘 보상을 다시 시작하는 경로") {
                next shouldBe SagaStatus.COMPENSATING
            }
        }

        When("COMPENSATING에 COMPENSATION_DONE을 보내면") {
            val next = stateMachine.transition("saga-1", SagaStatus.COMPENSATING, SagaEvent.COMPENSATION_DONE)

            Then("COMPENSATED로 간다") {
                next shouldBe SagaStatus.COMPENSATED
            }
        }

        When("COMPENSATING에 COMPENSATION_FAIL을 보내면") {
            val next = stateMachine.transition("saga-1", SagaStatus.COMPENSATING, SagaEvent.COMPENSATION_FAIL)

            Then("COMPENSATION_FAILED로 간다") {
                next shouldBe SagaStatus.COMPENSATION_FAILED
            }
        }

        When("COMPENSATION_FAILED에 COMPENSATE를 보내면") {
            val next = stateMachine.transition("saga-1", SagaStatus.COMPENSATION_FAILED, SagaEvent.COMPENSATE)

            Then("워커가 다시 보상할 수 있도록 COMPENSATING으로 돌아간다") {
                next shouldBe SagaStatus.COMPENSATING
            }
        }

        When("이미 SUCCEEDED인 사가에 COMPLETE를 다시 보내면") {
            val exception = shouldThrow<BusinessException> {
                stateMachine.transition("saga-1", SagaStatus.SUCCEEDED, SagaEvent.COMPLETE)
            }

            Then("INVALID_SAGA_STATE_TRANSITION이고 메시지에 상태와 이벤트가 담긴다") {
                exception.errorCode shouldBe OrderErrorCode.INVALID_SAGA_STATE_TRANSITION
                exception.message shouldContain "sagaId=saga-1"
                exception.message shouldContain "status=SUCCEEDED, event=COMPLETE"
            }
        }

        When("COMPENSATED인 사가에 COMPENSATE를 보내면") {
            val exception = shouldThrow<BusinessException> {
                stateMachine.transition("saga-1", SagaStatus.COMPENSATED, SagaEvent.COMPENSATE)
            }

            Then("끝난 보상을 다시 시작할 수 없다") {
                exception.errorCode shouldBe OrderErrorCode.INVALID_SAGA_STATE_TRANSITION
            }
        }

        When("RUNNING에 COMPENSATION_DONE을 보내면") {
            val exception = shouldThrow<BusinessException> {
                stateMachine.transition("saga-1", SagaStatus.RUNNING, SagaEvent.COMPENSATION_DONE)
            }

            Then("보상을 시작하지 않고 끝낼 수 없다") {
                exception.errorCode shouldBe OrderErrorCode.INVALID_SAGA_STATE_TRANSITION
            }
        }
    }

    Given("B안 응답 판정용 이벤트") {
        val stateMachine = SagaStateMachine()

        When("COMPENSATION_FAILED에 COMPENSATION_DONE을 보내면") {
            val next = stateMachine.transition("saga-1", SagaStatus.COMPENSATION_FAILED, SagaEvent.COMPENSATION_DONE)

            Then("마지막 보상 응답이 늦게 와도 COMPENSATED로 닫힌다") {
                next shouldBe SagaStatus.COMPENSATED
            }
        }

        When("RUNNING에 PROCEED를 보내면") {
            val next = stateMachine.transition("saga-1", SagaStatus.RUNNING, SagaEvent.PROCEED)

            Then("상태를 바꾸지 않는 내부 전이다") {
                next shouldBe SagaStatus.RUNNING
            }
        }

        Then("정방향 응답은 RUNNING 에서만 해당한다") {
            SagaStatus.entries.filter { stateMachine.accepts("saga-1", it, SagaEvent.PROCEED) } shouldBe listOf(SagaStatus.RUNNING)
        }

        Then("보상 응답은 COMPENSATING · COMPENSATION_FAILED 에서만 해당한다") {
            SagaStatus.entries.filter { stateMachine.accepts("saga-1", it, SagaEvent.RECORD_CANCEL) } shouldBe
                listOf(SagaStatus.COMPENSATING, SagaStatus.COMPENSATION_FAILED)
        }

        When("COMPENSATED에 RECORD_CANCEL을 보내면") {
            val exception = shouldThrow<BusinessException> {
                stateMachine.transition("saga-1", SagaStatus.COMPENSATED, SagaEvent.RECORD_CANCEL)
            }

            Then("닫힌 사가라 거부한다") {
                exception.errorCode shouldBe OrderErrorCode.INVALID_SAGA_STATE_TRANSITION
            }
        }
    }
})
