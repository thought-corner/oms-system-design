package com.project.point.service

import com.project.common.exception.BusinessException
import com.project.point.domain.PointTransactionHistory
import com.project.point.domain.PointTransactionType
import com.project.point.domain.SagaGuardKind
import com.project.point.exception.PointErrorCode
import com.project.point.fixture.PointFixture
import com.project.point.repository.PointRepository
import com.project.point.repository.PointTransactionHistoryRepository
import com.project.point.repository.SagaGuardRepository
import com.project.point.service.dto.PointMessageType
import com.project.point.service.dto.SagaDirection
import com.project.point.service.dto.SagaOutcome
import com.project.point.service.dto.SagaReply
import com.project.point.service.dto.UseCancelCommand
import com.project.point.service.dto.UseCancelResult
import com.project.point.service.dto.UseCommand
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.Called
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.mockk.verifyOrder
import java.time.Clock
import java.time.ZoneId

private val FIXED_CLOCK: Clock =
    Clock.fixed(PointFixture.SEED_TIME.atZone(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault())

private fun useCommand(userId: Long, amount: Long, sagaId: String = "saga-1") =
    UseCommand(sagaId = sagaId, orderId = 1L, userId = userId, amount = amount)

private fun guardRepository(kind: SagaGuardKind = SagaGuardKind.FORWARD): SagaGuardRepository =
    mockk<SagaGuardRepository>().also {
        every { it.insertIfAbsent(any(), any(), any()) } just Runs
        every { it.findWithLockBySagaId(any()) } answers { PointFixture.guard(sagaId = firstArg(), kind = kind) }
    }

private fun replyOutbox(): SagaReplyOutbox =
    mockk<SagaReplyOutbox>().also { every { it.append(any(), any()) } just Runs }

private fun pointService(
    pointRepository: PointRepository,
    historyRepository: PointTransactionHistoryRepository,
    guardRepository: SagaGuardRepository = guardRepository(),
    replyOutbox: SagaReplyOutbox = replyOutbox(),
): PointService =
    PointService(pointRepository, historyRepository, SagaGuardLock(guardRepository, FIXED_CLOCK), replyOutbox, FIXED_CLOCK)

class PointServiceTest : BehaviorSpec({

    Given("포인트가 없는 사용자 99") {
        val pointRepository = mockk<PointRepository>()
        val historyRepository = mockk<PointTransactionHistoryRepository>()
        val service = pointService(pointRepository, historyRepository)
        every { historyRepository.findBySagaIdAndTransactionType(any(), any()) } returns null
        every { pointRepository.findWithLockByUserId(99L) } returns null

        When("포인트를 쓰면") {
            val exception = shouldThrow<BusinessException> { service.use(useCommand(99L, 1L)) }

            Then("POINT_NOT_FOUND") {
                exception.errorCode shouldBe PointErrorCode.POINT_NOT_FOUND
                exception.message shouldContain "userId=99"
            }
        }
    }

    Given("잔액 399인 사용자 1") {
        val pointRepository = mockk<PointRepository>()
        val historyRepository = mockk<PointTransactionHistoryRepository>()
        val replyOutbox = replyOutbox()
        val service = pointService(pointRepository, historyRepository, replyOutbox = replyOutbox)
        val point = PointFixture.point(amount = 399L)
        every { historyRepository.findBySagaIdAndTransactionType(any(), any()) } returns null
        every { pointRepository.findWithLockByUserId(1L) } returns point

        When("400을 쓰면") {
            val exception = shouldThrow<BusinessException> { service.use(useCommand(1L, 400L)) }

            Then("INSUFFICIENT_POINT이고 잔액은 그대로이며 이력을 남기지 않는다") {
                exception.errorCode shouldBe PointErrorCode.INSUFFICIENT_POINT
                point.amount shouldBe 399L
                verify(exactly = 0) { historyRepository.save(any()) }
                verify { replyOutbox wasNot Called }
            }
        }
    }

    Given("잔액 10000인 사용자 1") {
        val pointRepository = mockk<PointRepository>()
        val historyRepository = mockk<PointTransactionHistoryRepository>()
        val guardRepository = guardRepository()
        val replyOutbox = replyOutbox()
        val service = pointService(pointRepository, historyRepository, guardRepository, replyOutbox)
        val reply = slot<SagaReply>()
        val point = PointFixture.point()
        every { historyRepository.findBySagaIdAndTransactionType(any(), any()) } returns null
        every { pointRepository.findWithLockByUserId(1L) } returns point
        every { historyRepository.save(any()) } answers { firstArg() }

        When("400을 쓰면") {
            service.use(useCommand(1L, 400L))

            Then("사가 가드를 FORWARD로 잡은 뒤 이력을 읽고 행 락으로 잔액을 차감해 USE 이력과 성공 응답을 같은 트랜잭션에 남긴다") {
                point.amount shouldBe 9600L
                verifyOrder {
                    guardRepository.insertIfAbsent("saga-1", SagaGuardKind.FORWARD.name, any())
                    guardRepository.findWithLockBySagaId("saga-1")
                    historyRepository.findBySagaIdAndTransactionType("saga-1", any())
                    pointRepository.findWithLockByUserId(1L)
                    historyRepository.save(any())
                    replyOutbox.append(PointMessageType.POINT_USE, capture(reply))
                }
                reply.captured.outcome shouldBe SagaOutcome.SUCCEEDED
                reply.captured.direction shouldBe SagaDirection.FORWARD
                reply.captured.step shouldBe "POINT"
                reply.captured.code shouldBe null
                reply.captured.result shouldBe emptyMap<String, Any>()
                verify(exactly = 0) { pointRepository.findById(any()) }
                verify(exactly = 1) {
                    historyRepository.save(match<PointTransactionHistory> { it.transactionType == PointTransactionType.USE })
                }
            }
        }
    }

    Given("같은 sagaId로 이미 사용한 이력이 있는 사용자") {
        val pointRepository = mockk<PointRepository>()
        val historyRepository = mockk<PointTransactionHistoryRepository>()
        val replyOutbox = replyOutbox()
        val service = pointService(pointRepository, historyRepository, replyOutbox = replyOutbox)
        every {
            historyRepository.findBySagaIdAndTransactionType("saga-1", PointTransactionType.CANCEL)
        } returns null
        every {
            historyRepository.findBySagaIdAndTransactionType("saga-1", PointTransactionType.USE)
        } returns PointFixture.history()

        When("같은 sagaId로 다시 사용을 요청하면") {
            service.use(useCommand(1L, 400L))

            Then("잔액을 건드리지 않고 성공 응답을 다시 남긴다") {
                verify(exactly = 0) { pointRepository.findWithLockByUserId(any()) }
                verify(exactly = 0) { historyRepository.save(any()) }
                verify(exactly = 1) {
                    replyOutbox.append(PointMessageType.POINT_USE, match { it.outcome == SagaOutcome.SUCCEEDED && it.sagaId == "saga-1" })
                }
            }
        }
    }

    Given("보상이 먼저 도착해 CANCEL 가드가 남은 sagaId") {
        val pointRepository = mockk<PointRepository>()
        val historyRepository = mockk<PointTransactionHistoryRepository>()
        val replyOutbox = replyOutbox()
        val service = pointService(pointRepository, historyRepository, guardRepository(SagaGuardKind.CANCEL), replyOutbox)

        When("늦게 도착한 사용 요청이 오면") {
            val exception = shouldThrow<BusinessException> { service.use(useCommand(1L, 400L)) }

            Then("SAGA_ALREADY_COMPENSATED로 거부하고 잔액도 응답도 남기지 않는다") {
                exception.errorCode shouldBe PointErrorCode.SAGA_ALREADY_COMPENSATED
                verify(exactly = 0) { pointRepository.findWithLockByUserId(any()) }
                verify(exactly = 0) { historyRepository.save(any()) }
                verify { replyOutbox wasNot Called }
            }
        }
    }

    Given("사용한 뒤 이미 되돌린 sagaId") {
        val pointRepository = mockk<PointRepository>()
        val historyRepository = mockk<PointTransactionHistoryRepository>()
        val service = pointService(pointRepository, historyRepository)
        every {
            historyRepository.findBySagaIdAndTransactionType("saga-1", PointTransactionType.CANCEL)
        } returns PointFixture.history(transactionType = PointTransactionType.CANCEL)

        When("같은 sagaId로 사용 요청이 다시 오면") {
            val exception = shouldThrow<BusinessException> { service.use(useCommand(1L, 400L)) }

            Then("SAGA_ALREADY_COMPENSATED로 거부한다") {
                exception.errorCode shouldBe PointErrorCode.SAGA_ALREADY_COMPENSATED
                verify(exactly = 0) { pointRepository.findWithLockByUserId(any()) }
            }
        }
    }

    Given("사용 이력이 없는 sagaId") {
        val pointRepository = mockk<PointRepository>()
        val historyRepository = mockk<PointTransactionHistoryRepository>()
        val guardRepository = guardRepository(SagaGuardKind.CANCEL)
        val replyOutbox = replyOutbox()
        val service = pointService(pointRepository, historyRepository, guardRepository, replyOutbox)
        every { historyRepository.findBySagaIdAndTransactionType("saga-none", PointTransactionType.USE) } returns null

        When("보상을 요청하면") {
            val refunded = service.cancel(UseCancelCommand(sagaId = "saga-none", orderId = 1L))

            Then("CANCEL 가드를 남기고 0을 돌려주며 실패 대신 0의 성공 응답을 남긴다") {
                refunded shouldBe 0L
                verify(exactly = 1) {
                    replyOutbox.append(
                        PointMessageType.POINT_CANCEL,
                        match {
                            it.outcome == SagaOutcome.SUCCEEDED && it.direction == SagaDirection.CANCEL && it.result == UseCancelResult(0L)
                        },
                    )
                }
                verify(exactly = 1) { guardRepository.insertIfAbsent("saga-none", SagaGuardKind.CANCEL.name, any()) }
                verify(exactly = 0) { pointRepository.findWithLockByUserId(any()) }
                verify(exactly = 0) { historyRepository.save(any()) }
            }
        }
    }

    Given("사용 이력이 있고 아직 되돌리지 않은 sagaId") {
        val pointRepository = mockk<PointRepository>()
        val historyRepository = mockk<PointTransactionHistoryRepository>()
        val replyOutbox = replyOutbox()
        val service = pointService(pointRepository, historyRepository, replyOutbox = replyOutbox)
        val point = PointFixture.point(amount = 9600L)
        every {
            historyRepository.findBySagaIdAndTransactionType("saga-1", PointTransactionType.USE)
        } returns PointFixture.history(orderId = 5L, amount = 400L)
        every {
            historyRepository.findBySagaIdAndTransactionType("saga-1", PointTransactionType.CANCEL)
        } returns null
        every { pointRepository.findWithLockByUserId(1L) } returns point
        every { historyRepository.save(any()) } answers { firstArg() }

        When("보상을 요청하면") {
            val refunded = service.cancel(UseCancelCommand(sagaId = "saga-1", orderId = 1L))

            Then("잔액이 10000으로 돌아오고 사용 이력의 주문으로 CANCEL 이력을 남긴다") {
                refunded shouldBe 400L
                point.amount shouldBe 10000L
                verify(exactly = 1) {
                    replyOutbox.append(PointMessageType.POINT_CANCEL, match { it.result == UseCancelResult(400L) })
                }
                verify(exactly = 1) {
                    historyRepository.save(
                        match<PointTransactionHistory> {
                            it.transactionType == PointTransactionType.CANCEL && it.orderId == 5L
                        },
                    )
                }
            }
        }
    }

    Given("이미 되돌린 sagaId") {
        val pointRepository = mockk<PointRepository>()
        val historyRepository = mockk<PointTransactionHistoryRepository>()
        val service = pointService(pointRepository, historyRepository)
        every {
            historyRepository.findBySagaIdAndTransactionType("saga-1", PointTransactionType.USE)
        } returns PointFixture.history(amount = 400L)
        every {
            historyRepository.findBySagaIdAndTransactionType("saga-1", PointTransactionType.CANCEL)
        } returns PointFixture.history(amount = 400L, transactionType = PointTransactionType.CANCEL)

        When("같은 sagaId로 보상을 다시 요청하면") {
            val refunded = service.cancel(UseCancelCommand(sagaId = "saga-1", orderId = 1L))

            Then("첫 번째 환불 금액을 그대로 돌려주고 잔액을 두 번 되돌리지 않는다") {
                refunded shouldBe 400L
                verify(exactly = 0) { pointRepository.findWithLockByUserId(any()) }
                verify(exactly = 0) { historyRepository.save(any()) }
            }
        }
    }

    Given("잔액 부족으로 사용 트랜잭션이 롤백된 사가") {
        val replyOutbox = replyOutbox()
        val service = pointService(mockk(), mockk(), replyOutbox = replyOutbox)
        val reply = slot<SagaReply>()

        When("실패 응답을 기록하면") {
            service.recordUseFailure(useCommand(1L, 400L), PointErrorCode.INSUFFICIENT_POINT)

            Then("비즈니스 변경 없이 POINT_USE 실패 응답만 남긴다") {
                verify(exactly = 1) { replyOutbox.append(PointMessageType.POINT_USE, capture(reply)) }
                reply.captured.outcome shouldBe SagaOutcome.FAILED
                reply.captured.code shouldBe "INSUFFICIENT_POINT"
                reply.captured.orderId shouldBe 1L
                reply.captured.result shouldBe null
            }
        }
    }
})
