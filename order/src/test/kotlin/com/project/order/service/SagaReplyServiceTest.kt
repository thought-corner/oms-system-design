package com.project.order.service

import com.project.common.exception.BusinessException
import com.project.common.exception.CommonErrorCode
import com.project.message.order.PaymentCancelCommand
import com.project.message.order.PaymentPayCommand
import com.project.message.order.PointCancelCommand
import com.project.message.order.PointUseCommand
import com.project.message.order.StockCancelCommand
import com.project.order.domain.OrderStatus
import com.project.order.domain.SagaStatus
import com.project.order.domain.SagaStep
import com.project.order.exception.OrderErrorCode
import com.project.order.fixture.CommandFixture
import com.project.order.fixture.OrderFixture
import com.project.order.service.dto.ReplyOutcome
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.verify
import org.springframework.dao.CannotAcquireLockException

class SagaReplyServiceTest : BehaviorSpec({

    fun SagaHarness.service() = SagaReplyService(orderRepository, sagaRepository, progress, commandOutbox, compensation, OrderFixture.FIXED_CLOCK)

    Given("재고 단계에서 응답을 기다리며 재발행을 두 번 겪은 사가") {
        val h = SagaHarness(saga = OrderFixture.sagaAt(SagaStep.STOCK, attempts = 2))

        When("재고 차감 성공 응답이 총액 400과 함께 오면") {
            h.service().handle(CommandFixture.succeeded(SagaStep.STOCK, totalPrice = 400L))

            Then("단계를 기록하고 같은 트랜잭션에서 POINT_USE 400을 outbox 에 넣고 시도 횟수를 비운다") {
                h.saga.stockDone shouldBe true
                h.saga.totalPrice shouldBe 400L
                h.saga.currentStep shouldBe SagaStep.POINT
                h.saga.attempts shouldBe 0
                h.saga.updatedAt shouldBe OrderFixture.FIXED_TIME
                h.messageTypes shouldContainExactly listOf("POINT_USE")
                val payload = PointUseCommand.parseFrom(h.payloadOf("POINT_USE"))
                payload.amount shouldBe 400L
                payload.userId shouldBe OrderFixture.DEFAULT_USER_ID
                payload.sagaId shouldBe OrderFixture.DEFAULT_SAGA_ID
                payload.orderId shouldBe OrderFixture.DEFAULT_ORDER_ID
                h.saved.single().topic shouldBe "cmd.point"
                h.saved.single().messageKey shouldBe "10"
            }
        }
    }

    Given("재고 단계의 사가에 총액이 빠진 성공 응답") {
        val h = SagaHarness(saga = OrderFixture.sagaAt(SagaStep.STOCK))

        When("처리하면") {
            val exception = shouldThrow<IllegalArgumentException> { h.service().handle(CommandFixture.succeeded(SagaStep.STOCK)) }

            Then("계약 위반이라 재시도 없이 DLT 로 갈 예외이고 아무것도 넣지 않는다") {
                exception.message shouldContain "totalPrice"
                h.saved.shouldBeEmpty()
                h.saga.stockDone shouldBe false
            }
        }
    }

    Given("포인트 단계의 사가") {
        val h = SagaHarness(saga = OrderFixture.sagaAt(SagaStep.POINT))

        When("포인트 사용 성공 응답이 result 없이 오면") {
            h.service().handle(CommandFixture.succeeded(SagaStep.POINT))

            Then("결제 단계로 넘어가며 PAYMENT_PAY 400을 넣는다") {
                h.saga.pointDone shouldBe true
                h.saga.currentStep shouldBe SagaStep.PAYMENT
                h.messageTypes shouldContainExactly listOf("PAYMENT_PAY")
                val payload = PaymentPayCommand.parseFrom(h.payloadOf("PAYMENT_PAY"))
                payload.amount shouldBe 400L
                payload.userId shouldBe OrderFixture.DEFAULT_USER_ID
                h.saved.single().topic shouldBe "cmd.payment"
            }
        }
    }

    Given("결제 단계의 사가") {
        val h = SagaHarness(saga = OrderFixture.sagaAt(SagaStep.PAYMENT))

        When("결제 성공 응답이 오면") {
            h.service().handle(CommandFixture.succeeded(SagaStep.PAYMENT))

            Then("AC-2 한 트랜잭션에서 결제 완료를 기록하고 주문 COMPLETED · 사가 SUCCEEDED 로 닫으며 커맨드는 없다") {
                h.saga.paymentDone shouldBe true
                h.saga.status shouldBe SagaStatus.SUCCEEDED
                h.order.status shouldBe OrderStatus.COMPLETED
                h.saved.shouldBeEmpty()
            }
        }
    }

    Given("결제 단계인데 주문이 PLACING 이 아닌 사가") {
        val h = SagaHarness(order = OrderFixture.order(status = OrderStatus.FAILED), saga = OrderFixture.sagaAt(SagaStep.PAYMENT))

        When("결제 성공 응답을 처리하면") {
            val exception = shouldThrow<BusinessException> { h.service().handle(CommandFixture.succeeded(SagaStep.PAYMENT)) }

            Then("A-23 로컬 완료 실패는 보상하지 않고 예외로 재배달에 맡기며 사가 상태는 그대로다") {
                exception.errorCode shouldBe OrderErrorCode.INVALID_ORDER_STATE_TRANSITION
                h.saga.status shouldBe SagaStatus.RUNNING
                h.saved.shouldBeEmpty()
            }
        }
    }

    Given("주문 행 락 대기가 초과되는 상황") {
        val h = SagaHarness(saga = OrderFixture.sagaAt(SagaStep.PAYMENT))
        every { h.orderRepository.findWithWaitingLockById(OrderFixture.DEFAULT_ORDER_ID) } throws CannotAcquireLockException("lock wait timeout")

        When("결제 성공 응답을 처리하면") {
            shouldThrow<CannotAcquireLockException> { h.service().handle(CommandFixture.succeeded(SagaStep.PAYMENT)) }

            Then("사가를 읽지도 보상하지도 않는다") {
                verify(exactly = 0) { h.sagaRepository.findWithWaitingLockBySagaId(any()) }
                h.saga.status shouldBe SagaStatus.RUNNING
                h.saved.shouldBeEmpty()
            }
        }
    }

    Given("이미 포인트 단계로 넘어간 사가") {
        val h = SagaHarness(saga = OrderFixture.sagaAt(SagaStep.POINT))

        When("재고 성공 응답이 한 번 더 오면") {
            h.service().handle(CommandFixture.succeeded(SagaStep.STOCK, totalPrice = 999L))

            Then("중복이라 무시하고 총액도 updated_at 도 바꾸지 않는다") {
                h.saga.totalPrice shouldBe OrderFixture.DEFAULT_TOTAL_PRICE
                h.saga.updatedAt shouldBe OrderFixture.STALE_TIME
                h.saved.shouldBeEmpty()
            }
        }

        When("재시도 토픽을 거친 옛 재고 실패 응답이 늦게 오면") {
            h.service().handle(CommandFixture.failed(SagaStep.STOCK, "INSUFFICIENT_STOCK"))

            Then("B-10 전진한 사가를 보상시키지 않는다") {
                h.saga.status shouldBe SagaStatus.RUNNING
                h.saga.failureCode.shouldBeNull()
                h.saga.updatedAt shouldBe OrderFixture.STALE_TIME
                h.saved.shouldBeEmpty()
            }
        }
    }

    Given("결제까지 승인됐지만 아직 완료로 닫히지 않은 사가") {
        val h = SagaHarness(saga = OrderFixture.sagaAt(SagaStep.PAYMENT, paymentDone = true))

        When("같은 단계의 결제 실패 응답이 늦게 오면") {
            h.service().handle(CommandFixture.failed(SagaStep.PAYMENT, "ALREADY_PAID"))

            Then("A-24 승인된 결제를 되돌리지 않는다") {
                h.saga.status shouldBe SagaStatus.RUNNING
                h.saved.shouldBeEmpty()
            }
        }
    }

    Given("포인트 단계의 사가에 잔액 부족 실패 응답") {
        val h = SagaHarness(saga = OrderFixture.sagaAt(SagaStep.POINT, attempts = 1))

        When("처리하면") {
            h.service().handle(CommandFixture.failed(SagaStep.POINT, "INSUFFICIENT_POINT"))

            Then("AC-4 COMPENSATING 으로 바꾸고 번역한 코드를 남기며 세 곳의 보상을 역순으로 넣고 주문은 PLACING 에 둔다") {
                h.saga.status shouldBe SagaStatus.COMPENSATING
                h.saga.failureCode shouldBe "INSUFFICIENT_POINT"
                h.saga.attempts shouldBe 0
                h.saga.updatedAt shouldBe OrderFixture.FIXED_TIME
                h.order.status shouldBe OrderStatus.PLACING
                h.messageTypes shouldContainExactly listOf("PAYMENT_CANCEL", "POINT_CANCEL", "STOCK_CANCEL")
                h.saved.map { it.topic } shouldContainExactly listOf("cmd.payment", "cmd.point", "cmd.product")
                val payload = StockCancelCommand.parseFrom(h.payloadOf("STOCK_CANCEL"))
                payload.sagaId shouldBe OrderFixture.DEFAULT_SAGA_ID
                payload.orderId shouldBe OrderFixture.DEFAULT_ORDER_ID
                PointCancelCommand.parseFrom(h.payloadOf("POINT_CANCEL")).sagaId shouldBe OrderFixture.DEFAULT_SAGA_ID
                PaymentCancelCommand.parseFrom(h.payloadOf("PAYMENT_CANCEL")).orderId shouldBe OrderFixture.DEFAULT_ORDER_ID
            }
        }
    }

    Given("재고 단계의 사가에 재고 부족 실패 응답") {
        val h = SagaHarness(saga = OrderFixture.sagaAt(SagaStep.STOCK))

        When("처리하면") {
            h.service().handle(CommandFixture.failed(SagaStep.STOCK, "INSUFFICIENT_STOCK"))

            Then("AC-3 A-18 차감 전이어도 세 곳 모두에 보상을 보낸다") {
                h.saga.failureCode shouldBe "INSUFFICIENT_STOCK"
                h.messageTypes shouldContainExactly listOf("PAYMENT_CANCEL", "POINT_CANCEL", "STOCK_CANCEL")
            }
        }
    }

    Given("보상이 먼저 도착해 참여자 가드가 거부한 늦은 정방향") {
        val h = SagaHarness(saga = OrderFixture.sagaAt(SagaStep.STOCK))

        When("SAGA_ALREADY_COMPENSATED 실패 응답이 현재 단계로 오면") {
            h.service().handle(CommandFixture.failed(SagaStep.STOCK, "SAGA_ALREADY_COMPENSATED"))

            Then("실패 사유가 아니므로 무시한다") {
                h.saga.status shouldBe SagaStatus.RUNNING
                h.saga.failureCode.shouldBeNull()
                h.saved.shouldBeEmpty()
            }
        }
    }

    Given("결제 단계의 사가에 order 가 모르는 실패 코드") {
        val h = SagaHarness(saga = OrderFixture.sagaAt(SagaStep.PAYMENT))

        When("처리하면") {
            h.service().handle(CommandFixture.failed(SagaStep.PAYMENT, "GATEWAY_EXPLODED"))

            Then("B-14 INTERNAL_ERROR 로 저장하고 원래 코드는 last_error 에 남기며 보상한다") {
                h.saga.failureCode shouldBe CommonErrorCode.INTERNAL_ERROR.code
                h.saga.lastError shouldContain "GATEWAY_EXPLODED"
                h.saga.status shouldBe SagaStatus.COMPENSATING
                h.messageTypes.size shouldBe 3
            }
        }
    }

    Given("재고 단계의 사가에 코드 없는 실패 응답") {
        val h = SagaHarness(saga = OrderFixture.sagaAt(SagaStep.STOCK))

        When("처리하면") {
            h.service().handle(CommandFixture.failed(SagaStep.STOCK, null))

            Then("INTERNAL_ERROR 로 보상을 시작한다") {
                h.saga.failureCode shouldBe CommonErrorCode.INTERNAL_ERROR.code
                h.saga.lastError shouldContain "code=null"
            }
        }
    }

    Given("잔액 부족으로 보상 중인 사가") {
        val h = SagaHarness(saga = OrderFixture.compensatingSaga(failureCode = "INSUFFICIENT_POINT"))

        When("같은 실패 응답이 중복으로 오면") {
            h.service().handle(CommandFixture.failed(SagaStep.POINT, "POINT_NOT_FOUND"))

            Then("첫 코드를 덮지 않고 보상도 다시 내지 않는다") {
                h.saga.failureCode shouldBe "INSUFFICIENT_POINT"
                h.saved.shouldBeEmpty()
            }
        }

        When("보상 응답이 결제·포인트 순으로 오고 포인트가 한 번 더 오면") {
            h.service().handle(CommandFixture.canceled(SagaStep.PAYMENT))
            h.service().handle(CommandFixture.canceled(SagaStep.POINT))
            val beforeDuplicate = h.saga.updatedAt
            h.service().handle(CommandFixture.canceled(SagaStep.POINT))

            Then("B-10 개수가 아니라 참여자별로 기록해 셋이 오기 전에는 닫지 않는다") {
                h.saga.paymentCanceled shouldBe true
                h.saga.pointCanceled shouldBe true
                h.saga.stockCanceled shouldBe false
                h.saga.status shouldBe SagaStatus.COMPENSATING
                h.order.status shouldBe OrderStatus.PLACING
                h.saga.updatedAt shouldBe beforeDuplicate
                h.saved.shouldBeEmpty()
            }
        }

        When("마지막 재고 복구 응답이 오면") {
            h.service().handle(CommandFixture.canceled(SagaStep.STOCK))

            Then("같은 트랜잭션에서 사가 COMPENSATED · 주문 FAILED 로 닫는다") {
                h.saga.stockCanceled shouldBe true
                h.saga.status shouldBe SagaStatus.COMPENSATED
                h.order.status shouldBe OrderStatus.FAILED
            }
        }

        When("닫힌 뒤 보상 응답이 또 오면") {
            h.service().handle(CommandFixture.canceled(SagaStep.STOCK))

            Then("무시한다") {
                h.saga.status shouldBe SagaStatus.COMPENSATED
                h.order.status shouldBe OrderStatus.FAILED
            }
        }
    }

    Given("워치독이 COMPENSATION_FAILED 로 바꾼 뒤 재고 복구만 남은 사가") {
        val h = SagaHarness(
            saga = OrderFixture.compensatingSaga(status = SagaStatus.COMPENSATION_FAILED, canceled = listOf(SagaStep.PAYMENT, SagaStep.POINT), attempts = 3),
        )

        When("재고 복구 응답이 오면") {
            h.service().handle(CommandFixture.canceled(SagaStep.STOCK))

            Then("COMPENSATION_FAILED 에서도 닫는다") {
                h.saga.status shouldBe SagaStatus.COMPENSATED
                h.order.status shouldBe OrderStatus.FAILED
            }
        }
    }

    Given("워치독이 COMPENSATION_FAILED 로 바꾼 뒤 두 곳이 남은 사가") {
        val h = SagaHarness(saga = OrderFixture.compensatingSaga(status = SagaStatus.COMPENSATION_FAILED, attempts = 3))

        When("결제 취소 응답이 오면") {
            h.service().handle(CommandFixture.canceled(SagaStep.PAYMENT))

            Then("진전으로 보고 시도 횟수를 비우지만 상태는 그대로 둔다") {
                h.saga.paymentCanceled shouldBe true
                h.saga.attempts shouldBe 0
                h.saga.status shouldBe SagaStatus.COMPENSATION_FAILED
            }
        }
    }

    Given("정방향 중인 사가") {
        val h = SagaHarness(saga = OrderFixture.sagaAt(SagaStep.POINT))

        When("보상 응답이 오면") {
            h.service().handle(CommandFixture.canceled(SagaStep.STOCK))

            Then("보상 중이 아니므로 무시한다") {
                h.saga.stockCanceled shouldBe false
                h.saga.updatedAt shouldBe OrderFixture.STALE_TIME
            }
        }
    }

    Given("보상 중인 사가에 FAILED 인 보상 응답") {
        val h = SagaHarness(saga = OrderFixture.compensatingSaga())

        When("처리하면") {
            h.service().handle(CommandFixture.canceled(SagaStep.STOCK, outcome = ReplyOutcome.FAILED))

            Then("계약에 없는 응답이라 기록하지 않는다") {
                h.saga.stockCanceled shouldBe false
            }
        }
    }

    Given("보상 중인 사가에 늦은 정방향 성공 응답") {
        val h = SagaHarness(saga = OrderFixture.compensatingSaga())

        When("처리하면") {
            h.service().handle(CommandFixture.succeeded(SagaStep.POINT))

            Then("B-9 실패 뒤 성공은 무시한다 — 보상이 세 곳 모두에 갔다") {
                h.saga.pointDone shouldBe false
                h.saga.status shouldBe SagaStatus.COMPENSATING
                h.saved.shouldBeEmpty()
            }
        }
    }

    Given("order 가 모르는 사가·주문의 응답") {
        val h = SagaHarness()
        every { h.sagaRepository.findWithWaitingLockBySagaId("saga-unknown") } returns null
        every { h.orderRepository.findWithWaitingLockById(OrderFixture.DEFAULT_ORDER_ID) } returnsMany listOf(h.order, null, h.order)
        every { h.sagaRepository.findWithWaitingLockBySagaId("saga-other") } returns OrderFixture.saga(sagaId = "saga-other", orderId = 99L)

        When("없는 sagaId · 없는 주문 · 다른 주문의 사가로 오면") {
            h.service().handle(CommandFixture.succeeded(SagaStep.STOCK, 400L, sagaId = "saga-unknown"))
            h.service().handle(CommandFixture.succeeded(SagaStep.STOCK, 400L))
            h.service().handle(CommandFixture.succeeded(SagaStep.STOCK, 400L, sagaId = "saga-other"))

            Then("예외 없이 무시하고 ack 한다") {
                h.saga.stockDone shouldBe false
                h.saved.shouldBeEmpty()
            }
        }
    }
})
