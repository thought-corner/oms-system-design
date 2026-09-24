package com.project.order.service

import com.project.order.client.AlertSender
import com.project.order.client.ForwardRecoveryFailedAlert
import com.project.order.domain.SagaStatus
import com.project.order.fixture.OrderFixture
import com.project.order.service.policy.SagaRecoveryPolicy
import com.project.order.service.worker.CompensationWorker
import io.kotest.core.spec.style.BehaviorSpec
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder

private fun stuck(status: SagaStatus, sagaId: String = OrderFixture.DEFAULT_SAGA_ID, paymentDone: Boolean = false) =
    OrderSagaStateService.StuckSaga(
        sagaId = sagaId,
        orderId = OrderFixture.DEFAULT_ORDER_ID,
        status = status,
        paymentDone = paymentDone,
    )

private fun forwardFailure(attempts: Int) = ForwardRecoveryFailedAlert(
    sagaId = OrderFixture.DEFAULT_SAGA_ID,
    orderId = OrderFixture.DEFAULT_ORDER_ID,
    attempts = attempts,
    lastError = "lock wait timeout",
)

private class WorkerFixture {
    val sagaState: OrderSagaStateService = mockk(relaxed = true)
    val orchestrator: SagaOrchestrator = mockk(relaxed = true)
    val alertSender: AlertSender = mockk(relaxed = true)
    val worker = CompensationWorker(sagaState, orchestrator, alertSender)

    fun found(vararg sagas: OrderSagaStateService.StuckSaga) = apply {
        every { sagaState.findStuck() } returns sagas.map { it.sagaId }
        sagas.forEach {
            every { sagaState.claim(it.sagaId) } returns it
            every { sagaState.contextOf(it.sagaId) } returns OrderFixture.context(sagaId = it.sagaId)
        }
    }

    fun failingCompletion(recorded: ForwardRecoveryFailedAlert?) = apply {
        found(stuck(SagaStatus.RUNNING, paymentDone = true))
        every { sagaState.succeed(any(), any()) } throws IllegalStateException("lock wait timeout")
        every { sagaState.forwardRecoveryFailed(OrderFixture.DEFAULT_SAGA_ID, "lock wait timeout") } returns recorded
    }
}

class CompensationWorkerTest : BehaviorSpec({

    Given("멈춘 사가가 없는 상태") {
        val f = WorkerFixture().found()

        When("워커가 깨어나면") {
            f.worker.sweep()

            Then("아무것도 하지 않는다") {
                verify(exactly = 0) { f.sagaState.claim(any()) }
                verify(exactly = 0) { f.orchestrator.run(any()) }
                verify(exactly = 0) { f.orchestrator.compensate(any(), any(), any()) }
            }
        }
    }

    Given("오케스트레이터가 죽어 RUNNING으로 남은 고아 사가") {
        val f = WorkerFixture().found(stuck(SagaStatus.RUNNING))
        every { f.orchestrator.run(any()) } just Runs

        When("워커가 깨어나면") {
            f.worker.sweep()

            Then("처리 직전에 사가를 집고 정방향을 처음부터 다시 실행한다") {
                verifyOrder {
                    f.sagaState.claim(OrderFixture.DEFAULT_SAGA_ID)
                    f.orchestrator.run(any())
                }
                verify(exactly = 0) { f.orchestrator.compensate(any(), any(), any()) }
            }
        }
    }

    Given("결제까지 성공했지만 완료 커밋 전에 멈춘 RUNNING 사가") {
        val f = WorkerFixture().found(stuck(SagaStatus.RUNNING, paymentDone = true))

        When("워커가 깨어나면") {
            f.worker.sweep()

            Then("정방향을 다시 돌리지 않고 완료로만 닫는다") {
                verify(exactly = 1) { f.sagaState.succeed(OrderFixture.DEFAULT_SAGA_ID, OrderFixture.DEFAULT_ORDER_ID) }
                verify(exactly = 0) { f.orchestrator.run(any()) }
                verify(exactly = 0) { f.orchestrator.compensate(any(), any(), any()) }
            }
        }
    }

    Given("결제까지 성공한 사가인데 완료로 닫기가 처음 실패하는 경우") {
        val f = WorkerFixture().failingCompletion(forwardFailure(1))

        When("워커가 깨어나면") {
            f.worker.sweep()

            Then("보상하지 않고 실패만 기록한 채 RUNNING으로 두며 알림은 아직 없다") {
                verify(exactly = 1) { f.sagaState.forwardRecoveryFailed(OrderFixture.DEFAULT_SAGA_ID, "lock wait timeout") }
                verify(exactly = 0) { f.orchestrator.compensate(any(), any(), any()) }
                verify(exactly = 0) { f.sagaState.beginCompensation(any(), any()) }
                verify(exactly = 0) { f.alertSender.send(any<ForwardRecoveryFailedAlert>()) }
            }
        }
    }

    Given("결제까지 성공한 사가인데 완료로 닫기가 상한만큼 실패한 경우") {
        val alert = forwardFailure(SagaRecoveryPolicy.FORWARD_RECOVERY_ALERT_ATTEMPTS)
        val f = WorkerFixture().failingCompletion(alert)

        When("워커가 깨어나면") {
            f.worker.sweep()

            Then("알림을 보내고 여전히 보상하지 않는다") {
                verify(exactly = 1) { f.alertSender.send(alert) }
                verify(exactly = 0) { f.orchestrator.compensate(any(), any(), any()) }
            }
        }
    }

    Given("결제까지 성공한 사가인데 완료로 닫기가 상한을 넘겨 실패한 경우") {
        val f = WorkerFixture().failingCompletion(forwardFailure(SagaRecoveryPolicy.FORWARD_RECOVERY_ALERT_ATTEMPTS + 1))

        When("워커가 깨어나면") {
            f.worker.sweep()

            Then("알림을 반복하지 않고 계속 재시도 대상으로 둔다") {
                verify(exactly = 0) { f.alertSender.send(any<ForwardRecoveryFailedAlert>()) }
                verify(exactly = 0) { f.orchestrator.compensate(any(), any(), any()) }
            }
        }
    }

    Given("완료로 닫기가 실패했지만 그사이 다른 주체가 이미 완료로 닫은 사가") {
        val f = WorkerFixture().failingCompletion(null)

        When("워커가 깨어나면") {
            f.worker.sweep()

            Then("실패로 보지 않고 알림도 보내지 않는다") {
                verify(exactly = 0) { f.alertSender.send(any<ForwardRecoveryFailedAlert>()) }
                verify(exactly = 0) { f.orchestrator.compensate(any(), any(), any()) }
            }
        }
    }

    Given("후보로 골랐지만 그사이 다른 워커가 집어 간 사가") {
        val f = WorkerFixture().found(stuck(SagaStatus.RUNNING))
        every { f.sagaState.claim(OrderFixture.DEFAULT_SAGA_ID) } returns null

        When("워커가 깨어나면") {
            f.worker.sweep()

            Then("건너뛰고 원격 호출을 하지 않는다") {
                verify(exactly = 0) { f.sagaState.contextOf(any()) }
                verify(exactly = 0) { f.orchestrator.run(any()) }
                verify(exactly = 0) { f.orchestrator.compensate(any(), any(), any()) }
            }
        }
    }

    Given("재개해도 다시 실패하는 고아 사가") {
        val f = WorkerFixture().found(stuck(SagaStatus.RUNNING))
        every { f.orchestrator.run(any()) } throws IllegalStateException("stock gone")

        When("워커가 깨어나면") {
            f.worker.sweep()

            Then("예외를 삼킨다 — 보상은 run 안에서 이미 돌았고 다음 사가 처리를 막지 않는다") {
                verify(exactly = 1) { f.orchestrator.run(any()) }
            }
        }
    }

    Given("보상이 재시도를 소진해 COMPENSATION_FAILED로 남은 사가") {
        val f = WorkerFixture().found(stuck(SagaStatus.COMPENSATION_FAILED))

        When("워커가 깨어나면") {
            f.worker.sweep()

            Then("보상을 다시 시도한다") {
                verify(exactly = 1) { f.orchestrator.compensate(any(), any(), any()) }
                verify(exactly = 0) { f.orchestrator.run(any()) }
            }
        }
    }

    Given("보상 도중 프로세스가 죽어 COMPENSATING으로 남은 사가") {
        val f = WorkerFixture().found(stuck(SagaStatus.COMPENSATING))

        When("워커가 깨어나면") {
            f.worker.sweep()

            Then("보상을 이어서 마무리한다") {
                verify(exactly = 1) { f.orchestrator.compensate(any(), any(), any()) }
            }
        }
    }

    Given("한 건이 터지고 다음 건은 멀쩡한 두 사가") {
        val broken = stuck(SagaStatus.COMPENSATING, sagaId = "saga-broken")
        val healthy = stuck(SagaStatus.COMPENSATING, sagaId = "saga-healthy")
        val f = WorkerFixture().found(broken, healthy)
        every { f.sagaState.contextOf("saga-broken") } throws IllegalStateException("order gone")

        When("워커가 깨어나면") {
            f.worker.sweep()

            Then("한 건의 실패가 나머지 처리를 막지 않는다") {
                verify(exactly = 1) { f.orchestrator.compensate(match { it.sagaId == "saga-healthy" }, any(), any()) }
            }
        }
    }
})
