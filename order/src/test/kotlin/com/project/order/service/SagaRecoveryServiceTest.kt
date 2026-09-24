package com.project.order.service

import com.project.order.client.CompensationFailedAlert
import com.project.order.client.ForwardRecoveryFailedAlert
import com.project.order.client.OutboxStalledAlert
import com.project.order.domain.OrderStatus
import com.project.order.domain.OutboxMessage
import com.project.order.domain.OutboxStatus
import com.project.order.domain.SagaStatus
import com.project.order.domain.SagaStep
import com.project.order.fixture.OrderFixture
import com.project.order.service.dto.StuckSaga
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.verify
import org.springframework.data.domain.Limit

private val STUCK = StuckSaga(OrderFixture.DEFAULT_SAGA_ID, OrderFixture.DEFAULT_ORDER_ID)

private fun SagaHarness.recovery(unpublished: List<OutboxMessage> = emptyList()): SagaRecoveryService {
    every { sagaRepository.findWithLockBySagaIdAndStatusInAndUpdatedAtLessThan(saga.sagaId, any(), any()) } returns saga
    every { outboxRepository.findAllBySagaIdAndStatusInOrderByIdAsc(saga.sagaId, any()) } returns unpublished
    return SagaRecoveryService(orderRepository, sagaRepository, outboxRepository, progress, commandOutbox, OrderFixture.FIXED_CLOCK)
}

class SagaRecoveryServiceTest : BehaviorSpec({

    Given("임계를 넘긴 사가 둘") {
        val h = SagaHarness()
        every { h.sagaRepository.findByStatusInAndUpdatedAtLessThanOrderByUpdatedAtAsc(any(), any(), any()) } returns
            listOf(OrderFixture.saga(sagaId = "saga-a"), OrderFixture.saga(sagaId = "saga-b", orderId = 11L))

        When("후보를 고르면") {
            val stuck = h.recovery().findStuck()

            Then("RUNNING·COMPENSATING·COMPENSATION_FAILED 중 60초 넘게 멈춘 것을 20건까지 sagaId·orderId 로 돌려준다") {
                stuck shouldContainExactly listOf(StuckSaga("saga-a", 10L), StuckSaga("saga-b", 11L))
                verify {
                    h.sagaRepository.findByStatusInAndUpdatedAtLessThanOrderByUpdatedAtAsc(
                        listOf(SagaStatus.RUNNING, SagaStatus.COMPENSATING, SagaStatus.COMPENSATION_FAILED),
                        OrderFixture.FIXED_TIME.minusSeconds(60),
                        Limit.of(20),
                    )
                }
            }
        }
    }

    Given("다른 인스턴스가 이미 집은 사가") {
        val h = SagaHarness(saga = OrderFixture.sagaAt(SagaStep.STOCK))
        val service = h.recovery()
        every { h.sagaRepository.findWithLockBySagaIdAndStatusInAndUpdatedAtLessThan(any(), any(), any()) } returns null

        When("복구하면") {
            val alert = service.recover(STUCK)

            Then("SKIP LOCKED 로 건너뛰고 아무것도 하지 않는다") {
                alert.shouldBeNull()
                h.saved.shouldBeEmpty()
                verify(exactly = 0) { h.outboxRepository.findAllBySagaIdAndStatusInOrderByIdAsc(any(), any()) }
            }
        }
    }

    Given("주문이 사라진 사가") {
        val h = SagaHarness(saga = OrderFixture.sagaAt(SagaStep.STOCK))
        val service = h.recovery()
        every { h.orderRepository.findWithWaitingLockById(any()) } returns null

        When("복구하면") {
            val alert = service.recover(STUCK)

            Then("사가를 집지 않는다") {
                alert.shouldBeNull()
                verify(exactly = 0) { h.sagaRepository.findWithLockBySagaIdAndStatusInAndUpdatedAtLessThan(any(), any(), any()) }
            }
        }
    }

    Given("방금 넣은 커맨드가 아직 발행되지 않은 RUNNING 사가") {
        val h = SagaHarness(saga = OrderFixture.sagaAt(SagaStep.POINT))
        val service = h.recovery(listOf(OrderFixture.outbox("POINT_USE", occurredAt = OrderFixture.FIXED_TIME.minusMinutes(4))))

        When("복구하면") {
            val alert = service.recover(STUCK)

            Then("B-11 재발행하지 않고 알리지도 않으며 임대만 갱신한다") {
                alert.shouldBeNull()
                h.saved.shouldBeEmpty()
                h.saga.attempts shouldBe 0
                h.saga.updatedAt shouldBe OrderFixture.FIXED_TIME
            }
        }
    }

    Given("5분 넘게 발행되지 않은 커맨드가 남은 사가") {
        val h = SagaHarness(saga = OrderFixture.sagaAt(SagaStep.POINT))
        val service = h.recovery(listOf(OrderFixture.outbox("POINT_USE", occurredAt = OrderFixture.FIXED_TIME.minusMinutes(6))))

        When("복구하면") {
            val alert = service.recover(STUCK)

            Then("재발행하지 않고 발행 정체를 알린다") {
                h.saved.shouldBeEmpty()
                val stalled = alert.shouldBeInstanceOf<OutboxStalledAlert>()
                stalled.messageType shouldBe "POINT_USE"
                stalled.status shouldBe "PENDING"
                stalled.failedCount shouldBe 0
            }
        }
    }

    Given("릴레이가 FAILED 로 포기한 커맨드가 남은 사가") {
        val h = SagaHarness(saga = OrderFixture.sagaAt(SagaStep.POINT))
        val service = h.recovery(
            listOf(
                OrderFixture.outbox("POINT_USE", status = OutboxStatus.FAILED, occurredAt = OrderFixture.FIXED_TIME.minusMinutes(1)),
                OrderFixture.outbox("POINT_USE", occurredAt = OrderFixture.FIXED_TIME.minusSeconds(30)),
            ),
        )

        When("복구하면") {
            val alert = service.recover(STUCK)

            Then("나이와 무관하게 재발행 없이 알린다") {
                h.saved.shouldBeEmpty()
                val stalled = alert.shouldBeInstanceOf<OutboxStalledAlert>()
                stalled.status shouldBe "FAILED"
                stalled.failedCount shouldBe 1
            }
        }
    }

    Given("이미 지나간 재고 단계의 FAILED 커맨드만 남은 포인트 단계 RUNNING 사가") {
        val h = SagaHarness(saga = OrderFixture.sagaAt(SagaStep.POINT))
        val service = h.recovery(listOf(OrderFixture.outbox("STOCK_BUY", status = OutboxStatus.FAILED, occurredAt = OrderFixture.FIXED_TIME.minusMinutes(10))))

        When("복구하면") {
            val alert = service.recover(STUCK)

            Then("지나간 단계의 FAILED 행은 막지 않아 현재 단계 커맨드를 다시 넣는다") {
                alert.shouldBeNull()
                h.messageTypes shouldContainExactly listOf("POINT_USE")
                h.saga.attempts shouldBe 1
            }
        }
    }

    Given("현재 단계 커맨드가 FAILED 로 남은 RUNNING 사가") {
        val h = SagaHarness(saga = OrderFixture.sagaAt(SagaStep.POINT))
        val service = h.recovery(listOf(OrderFixture.outbox("POINT_USE", status = OutboxStatus.FAILED, occurredAt = OrderFixture.FIXED_TIME.minusMinutes(1))))

        When("복구하면") {
            val alert = service.recover(STUCK)

            Then("재발행하지 않고 FAILED 행을 알린다") {
                h.saved.shouldBeEmpty()
                h.saga.attempts shouldBe 0
                val stalled = alert.shouldBeInstanceOf<OutboxStalledAlert>()
                stalled.messageType shouldBe "POINT_USE"
                stalled.status shouldBe "FAILED"
                stalled.failedCount shouldBe 1
            }
        }
    }

    Given("지나간 단계의 커맨드가 아직 PENDING 인 RUNNING 사가") {
        val h = SagaHarness(saga = OrderFixture.sagaAt(SagaStep.POINT))
        val service = h.recovery(
            listOf(
                OrderFixture.outbox("STOCK_BUY", status = OutboxStatus.FAILED, occurredAt = OrderFixture.FIXED_TIME.minusMinutes(10)),
                OrderFixture.outbox("STOCK_BUY", occurredAt = OrderFixture.FIXED_TIME.minusMinutes(6)),
            ),
        )

        When("복구하면") {
            val alert = service.recover(STUCK)

            Then("PENDING 행은 종류와 무관하게 재발행을 막고, 무시한 FAILED 행은 알림에 세지 않는다") {
                h.saved.shouldBeEmpty()
                val stalled = alert.shouldBeInstanceOf<OutboxStalledAlert>()
                stalled.messageType shouldBe "STOCK_BUY"
                stalled.status shouldBe "PENDING"
                stalled.failedCount shouldBe 0
            }
        }
    }

    Given("결제까지 승인됐고 결제 커맨드 재발행분이 FAILED 로 남은 사가") {
        val h = SagaHarness(saga = OrderFixture.sagaAt(SagaStep.PAYMENT, paymentDone = true))
        val service = h.recovery(listOf(OrderFixture.outbox("PAYMENT_PAY", status = OutboxStatus.FAILED)))

        When("복구하면") {
            val alert = service.recover(STUCK)

            Then("완료에는 커맨드가 필요 없으므로 막지 않고 완료 전이만 한다") {
                alert.shouldBeNull()
                h.saved.shouldBeEmpty()
                h.saga.status shouldBe SagaStatus.SUCCEEDED
            }
        }
    }

    Given("결제 취소 응답만 왔고 정방향 커맨드와 결제 취소 커맨드가 FAILED 로 남은 보상 중 사가") {
        val h = SagaHarness(saga = OrderFixture.compensatingSaga(canceled = listOf(SagaStep.PAYMENT)))
        val service = h.recovery(
            listOf(
                OrderFixture.outbox("POINT_USE", status = OutboxStatus.FAILED),
                OrderFixture.outbox("PAYMENT_CANCEL", status = OutboxStatus.FAILED),
            ),
        )

        When("복구하면") {
            val alert = service.recover(STUCK)

            Then("지금 기다리는 보상이 아닌 FAILED 행은 무시하고 남은 보상을 넣는다") {
                alert.shouldBeNull()
                h.messageTypes shouldContainExactly listOf("POINT_CANCEL", "STOCK_CANCEL")
            }
        }
    }

    Given("재고 보상 커맨드가 FAILED 로 남은 보상 중 사가") {
        val h = SagaHarness(saga = OrderFixture.compensatingSaga(canceled = listOf(SagaStep.PAYMENT, SagaStep.POINT)))
        val service = h.recovery(listOf(OrderFixture.outbox("STOCK_CANCEL", status = OutboxStatus.FAILED)))

        When("복구하면") {
            val alert = service.recover(STUCK)

            Then("재발행하지 않고 FAILED 행을 알린다") {
                h.saved.shouldBeEmpty()
                h.saga.status shouldBe SagaStatus.COMPENSATING
                val stalled = alert.shouldBeInstanceOf<OutboxStalledAlert>()
                stalled.messageType shouldBe "STOCK_CANCEL"
                stalled.failedCount shouldBe 1
            }
        }
    }

    Given("재고 응답이 오지 않는 RUNNING 사가") {
        val h = SagaHarness(saga = OrderFixture.sagaAt(SagaStep.STOCK))
        val service = h.recovery()

        When("복구하면") {
            val alert = service.recover(STUCK)

            Then("B-11 현재 단계 커맨드만 같은 sagaId 로 다시 넣고 시도를 센다") {
                alert.shouldBeNull()
                h.messageTypes shouldContainExactly listOf("STOCK_BUY")
                h.saved.single().sagaId shouldBe OrderFixture.DEFAULT_SAGA_ID
                h.saga.attempts shouldBe 1
                h.saga.status shouldBe SagaStatus.RUNNING
                h.saga.updatedAt shouldBe OrderFixture.FIXED_TIME
            }
        }
    }

    Given("포인트 커맨드를 두 번 재발행했는데도 응답이 없는 사가") {
        val h = SagaHarness(saga = OrderFixture.sagaAt(SagaStep.POINT, attempts = 2))
        val service = h.recovery()

        When("세 번째로 복구하면") {
            val alert = service.recover(STUCK)

            Then("재발행하고 ForwardRecoveryFailedAlert 를 한 번 낸다") {
                h.messageTypes shouldContainExactly listOf("POINT_USE")
                val forward = alert.shouldBeInstanceOf<ForwardRecoveryFailedAlert>()
                forward.step shouldBe "POINT"
                forward.attempts shouldBe 3
            }
        }
    }

    Given("포인트 커맨드를 세 번 재발행해도 진전이 없는 사가") {
        val h = SagaHarness(saga = OrderFixture.sagaAt(SagaStep.POINT, attempts = 3))
        val service = h.recovery()

        When("복구하면") {
            val alert = service.recover(STUCK)

            Then("시간으로 보상하지 않고 현재 단계 커맨드를 계속 재발행하며 알림은 3회째 한 번뿐이다") {
                alert.shouldBeNull()
                h.messageTypes shouldContainExactly listOf("POINT_USE")
                h.saga.status shouldBe SagaStatus.RUNNING
                h.saga.failureCode.shouldBeNull()
                h.saga.attempts shouldBe 4
                h.order.status shouldBe OrderStatus.PLACING
            }
        }
    }

    Given("결제 커맨드를 여러 번 재발행해도 응답이 없는 사가") {
        val h = SagaHarness(saga = OrderFixture.sagaAt(SagaStep.PAYMENT, attempts = 5))
        val service = h.recovery()

        When("복구하면") {
            val alert = service.recover(STUCK)

            Then("A-24 결제 단계부터는 시간으로 보상하지 않고 계속 재발행하며 알림은 3회째 한 번뿐이다") {
                alert.shouldBeNull()
                h.messageTypes shouldContainExactly listOf("PAYMENT_PAY")
                h.saga.status shouldBe SagaStatus.RUNNING
                h.saga.attempts shouldBe 6
            }
        }
    }

    Given("결제까지 승인됐지만 완료로 닫지 못한 사가") {
        val h = SagaHarness(saga = OrderFixture.sagaAt(SagaStep.PAYMENT, paymentDone = true))
        val service = h.recovery()

        When("복구하면") {
            val alert = service.recover(STUCK)

            Then("A-24 커맨드 없이 완료 전이만 한다") {
                alert.shouldBeNull()
                h.saved.shouldBeEmpty()
                h.saga.status shouldBe SagaStatus.SUCCEEDED
                h.order.status shouldBe OrderStatus.COMPLETED
            }
        }
    }

    Given("결제까지 승인됐는데 주문이 PLACING 이 아니어서 두 번 완료에 실패한 사가") {
        val h = SagaHarness(
            order = OrderFixture.order(status = OrderStatus.FAILED),
            saga = OrderFixture.sagaAt(SagaStep.PAYMENT, paymentDone = true, attempts = 2),
        )
        val service = h.recovery()

        When("세 번째로 복구하면") {
            val alert = service.recover(STUCK)

            Then("보상하지 않고 오류를 남기며 ForwardRecoveryFailedAlert 를 낸다") {
                h.saved.shouldBeEmpty()
                h.saga.status shouldBe SagaStatus.RUNNING
                h.order.status shouldBe OrderStatus.FAILED
                h.saga.lastError shouldContain "event=COMPLETE"
                alert.shouldBeInstanceOf<ForwardRecoveryFailedAlert>().attempts shouldBe 3
            }
        }
    }

    Given("결제 취소 응답만 온 보상 중 사가") {
        val h = SagaHarness(saga = OrderFixture.compensatingSaga(canceled = listOf(SagaStep.PAYMENT)))
        val service = h.recovery()

        When("복구하면") {
            val alert = service.recover(STUCK)

            Then("응답이 오지 않은 포인트·재고 보상만 다시 넣는다") {
                alert.shouldBeNull()
                h.messageTypes shouldContainExactly listOf("POINT_CANCEL", "STOCK_CANCEL")
                h.saga.status shouldBe SagaStatus.COMPENSATING
                h.saga.attempts shouldBe 1
            }
        }
    }

    Given("보상을 두 번 재발행해도 응답이 없는 사가") {
        val h = SagaHarness(saga = OrderFixture.compensatingSaga(canceled = listOf(SagaStep.PAYMENT, SagaStep.POINT), attempts = 2))
        val service = h.recovery()

        When("세 번째로 복구하면") {
            val alert = service.recover(STUCK)

            Then("재발행하고 COMPENSATION_FAILED 로 바꾸며 CompensationFailedAlert 를 한 번 낸다") {
                h.messageTypes shouldContainExactly listOf("STOCK_CANCEL")
                h.saga.status shouldBe SagaStatus.COMPENSATION_FAILED
                val compensation = alert.shouldBeInstanceOf<CompensationFailedAlert>()
                compensation.attempts shouldBe 3
                compensation.pendingCancels shouldContainExactly listOf("STOCK")
                h.order.status shouldBe OrderStatus.PLACING
            }
        }
    }

    Given("이미 알림을 낸 COMPENSATION_FAILED 사가") {
        val h = SagaHarness(saga = OrderFixture.compensatingSaga(status = SagaStatus.COMPENSATION_FAILED, attempts = 3))
        val service = h.recovery()

        When("다시 복구하면") {
            val alert = service.recover(STUCK)

            Then("COMPENSATE 로 재발행하되 사람이 볼 상태는 COMPENSATION_FAILED 로 남기고 알림은 반복하지 않는다") {
                alert.shouldBeNull()
                h.messageTypes shouldContainExactly listOf("PAYMENT_CANCEL", "POINT_CANCEL", "STOCK_CANCEL")
                h.saga.status shouldBe SagaStatus.COMPENSATION_FAILED
                h.saga.attempts shouldBe 4
            }
        }
    }

    Given("응답이 와서 시도 횟수가 비워진 COMPENSATION_FAILED 사가") {
        val h = SagaHarness(saga = OrderFixture.compensatingSaga(status = SagaStatus.COMPENSATION_FAILED, canceled = listOf(SagaStep.PAYMENT)))
        val service = h.recovery()

        When("복구하면") {
            service.recover(STUCK)

            Then("COMPENSATION_FAILED --COMPENSATE--> COMPENSATING 으로 돌아가 남은 보상을 넣는다") {
                h.saga.status shouldBe SagaStatus.COMPENSATING
                h.messageTypes shouldContainExactly listOf("POINT_CANCEL", "STOCK_CANCEL")
            }
        }
    }

    Given("세 보상 응답이 모두 기록됐는데 닫히지 않은 사가") {
        val h = SagaHarness(saga = OrderFixture.compensatingSaga(canceled = listOf(SagaStep.PAYMENT, SagaStep.POINT, SagaStep.STOCK)))
        val service = h.recovery()

        When("복구하면") {
            val alert = service.recover(STUCK)

            Then("커맨드 없이 COMPENSATED · FAILED 로 닫기만 한다") {
                alert.shouldBeNull()
                h.saved.shouldBeEmpty()
                h.saga.status shouldBe SagaStatus.COMPENSATED
                h.order.status shouldBe OrderStatus.FAILED
            }
        }
    }
})
