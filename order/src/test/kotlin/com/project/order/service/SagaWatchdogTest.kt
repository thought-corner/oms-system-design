package com.project.order.service

import com.project.order.client.AlertSender
import com.project.order.client.ForwardRecoveryFailedAlert
import com.project.order.service.dto.StuckSaga
import com.project.order.service.worker.SagaWatchdog
import io.kotest.core.spec.style.BehaviorSpec
import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify

class SagaWatchdogTest : BehaviorSpec({

    Given("멈춘 사가가 없는 상태") {
        val recovery = mockk<SagaRecoveryService>()
        val alertSender = mockk<AlertSender>(relaxed = true)
        every { recovery.findStuck() } returns emptyList()

        When("워치독이 깨어나면") {
            SagaWatchdog(recovery, alertSender).sweep()

            Then("아무것도 하지 않는다") {
                verify(exactly = 0) { recovery.recover(any()) }
                verify { alertSender wasNot Called }
            }
        }
    }

    Given("멈춘 사가 셋 — 하나는 알림을 돌려주고 하나는 복구 중 예외가 나는") {
        val recovery = mockk<SagaRecoveryService>()
        val alertSender = mockk<AlertSender>(relaxed = true)
        val alert = ForwardRecoveryFailedAlert("saga-a", 10L, "POINT", 3, "no reply for POINT")
        val a = StuckSaga("saga-a", 10L)
        val b = StuckSaga("saga-b", 11L)
        val c = StuckSaga("saga-c", 12L)
        every { recovery.findStuck() } returns listOf(a, b, c)
        every { recovery.recover(a) } returns alert
        every { recovery.recover(b) } throws IllegalStateException("deadlock")
        every { recovery.recover(c) } returns null

        When("워치독이 깨어나면") {
            SagaWatchdog(recovery, alertSender).sweep()

            Then("한 건의 실패가 다른 사가를 막지 않고 알림은 커밋 뒤에 한 번 보낸다") {
                verify(exactly = 1) { recovery.recover(c) }
                verify(exactly = 1) { alertSender.send(alert) }
                verify(exactly = 1) { alertSender.send(any()) }
            }
        }
    }
})
