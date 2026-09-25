package com.project.operation.service.worker

import com.project.operation.client.AlertSender
import com.project.operation.client.OutboxDelayAlert
import com.project.operation.domain.OutboxDelay
import com.project.operation.domain.OutboxSource
import com.project.operation.fixture.OutboxFixture
import com.project.operation.service.OutboxService
import io.kotest.core.spec.style.BehaviorSpec
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.springframework.dao.DataAccessResourceFailureException

class OutboxDelayMonitorTest : BehaviorSpec({

    Given("가장 오래된 PENDING 행이 6분 된 스키마, 1분 된 스키마, FAILED 행만 있는 스키마, 조회가 실패하는 스키마") {
        val outboxService: OutboxService = mockk()
        val alertSender: AlertSender = mockk(relaxed = true)
        val monitor = OutboxDelayMonitor(outboxService, alertSender, OutboxFixture.FIXED_CLOCK)
        val now = OutboxFixture.FIXED_TIME
        every { outboxService.delayOf(OutboxSource.ORDER) } returns OutboxDelay(12, now.minusMinutes(6), 2)
        every { outboxService.delayOf(OutboxSource.PRODUCT) } returns OutboxDelay(1, now.minusMinutes(1), 0)
        every { outboxService.delayOf(OutboxSource.POINT) } returns OutboxDelay(0, null, 3)
        every { outboxService.delayOf(OutboxSource.PAYMENT) } throws DataAccessResourceFailureException("down")

        When("관측이 돌면") {
            monitor.checkDelays()

            Then("5분을 넘긴 스키마만 FAILED 행 수와 함께 알린다") {
                verify(exactly = 1) { alertSender.send(OutboxDelayAlert("order", 12, now.minusMinutes(6), 360, 2)) }
                verify(exactly = 1) { alertSender.send(any<OutboxDelayAlert>()) }
            }
        }
    }
})
