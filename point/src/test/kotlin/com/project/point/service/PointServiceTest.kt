package com.project.point.service

import com.project.point.domain.PointTransactionHistory
import com.project.point.domain.PointTransactionType
import com.project.common.exception.BusinessException
import com.project.point.exception.PointErrorCode
import com.project.point.fixture.PointFixture
import com.project.point.repository.PointRepository
import com.project.point.repository.PointTransactionHistoryRepository
import com.project.point.service.dto.UseCancelCommand
import com.project.point.service.dto.UseCommand
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Clock
import java.time.ZoneId

private val FIXED_CLOCK: Clock =
    Clock.fixed(PointFixture.SEED_TIME.atZone(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault())

private fun useCommand(userId: Long, amount: Long, sagaId: String = "saga-1") =
    UseCommand(sagaId = sagaId, orderId = 1L, userId = userId, amount = amount)

class PointServiceTest : BehaviorSpec({

    Given("포인트가 없는 사용자 99") {
        val pointRepository = mockk<PointRepository>()
        val historyRepository = mockk<PointTransactionHistoryRepository>()
        val service = PointService(pointRepository, historyRepository, FIXED_CLOCK)
        every { historyRepository.findBySagaIdAndTransactionType(any(), PointTransactionType.USE) } returns null
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
        val service = PointService(pointRepository, historyRepository, FIXED_CLOCK)
        val point = PointFixture.point(amount = 399L)
        every { historyRepository.findBySagaIdAndTransactionType(any(), PointTransactionType.USE) } returns null
        every { pointRepository.findWithLockByUserId(1L) } returns point

        When("400을 쓰면") {
            val exception = shouldThrow<BusinessException> { service.use(useCommand(1L, 400L)) }

            Then("INSUFFICIENT_POINT이고 잔액은 그대로이며 이력을 남기지 않는다") {
                exception.errorCode shouldBe PointErrorCode.INSUFFICIENT_POINT
                point.amount shouldBe 399L
                verify(exactly = 0) { historyRepository.save(any()) }
            }
        }
    }

    Given("잔액 10000인 사용자 1") {
        val pointRepository = mockk<PointRepository>()
        val historyRepository = mockk<PointTransactionHistoryRepository>()
        val service = PointService(pointRepository, historyRepository, FIXED_CLOCK)
        val point = PointFixture.point()
        every { historyRepository.findBySagaIdAndTransactionType(any(), PointTransactionType.USE) } returns null
        every { pointRepository.findWithLockByUserId(1L) } returns point
        every { historyRepository.save(any()) } answers { firstArg() }

        When("400을 쓰면") {
            service.use(useCommand(1L, 400L))

            Then("행 락으로 읽어 잔액을 차감하고 USE 이력을 남긴다") {
                point.amount shouldBe 9600L
                verify(exactly = 1) { pointRepository.findWithLockByUserId(1L) }
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
        val service = PointService(pointRepository, historyRepository, FIXED_CLOCK)
        every {
            historyRepository.findBySagaIdAndTransactionType("saga-1", PointTransactionType.USE)
        } returns PointFixture.history()

        When("같은 sagaId로 다시 사용을 요청하면") {
            service.use(useCommand(1L, 400L))

            Then("잔액을 건드리지 않는다") {
                verify(exactly = 0) { pointRepository.findWithLockByUserId(any()) }
                verify(exactly = 0) { historyRepository.save(any()) }
            }
        }
    }

    Given("사용 이력이 없는 sagaId") {
        val pointRepository = mockk<PointRepository>()
        val historyRepository = mockk<PointTransactionHistoryRepository>()
        val service = PointService(pointRepository, historyRepository, FIXED_CLOCK)
        every { historyRepository.findBySagaIdAndTransactionType("saga-none", PointTransactionType.USE) } returns null

        When("보상을 요청하면") {
            val refunded = service.cancel(UseCancelCommand(sagaId = "saga-none", orderId = 1L))

            Then("되돌릴 것이 없으므로 0을 돌려주고 실패하지 않는다") {
                refunded shouldBe 0L
                verify(exactly = 0) { pointRepository.findWithLockByUserId(any()) }
            }
        }
    }

    Given("사용 이력이 있고 아직 되돌리지 않은 sagaId") {
        val pointRepository = mockk<PointRepository>()
        val historyRepository = mockk<PointTransactionHistoryRepository>()
        val service = PointService(pointRepository, historyRepository, FIXED_CLOCK)
        val point = PointFixture.point(amount = 9600L)
        every {
            historyRepository.findBySagaIdAndTransactionType("saga-1", PointTransactionType.USE)
        } returns PointFixture.history(amount = 400L)
        every {
            historyRepository.findBySagaIdAndTransactionType("saga-1", PointTransactionType.CANCEL)
        } returns null
        every { pointRepository.findWithLockByUserId(1L) } returns point
        every { historyRepository.save(any()) } answers { firstArg() }

        When("보상을 요청하면") {
            val refunded = service.cancel(UseCancelCommand(sagaId = "saga-1", orderId = 1L))

            Then("잔액이 10000으로 돌아오고 CANCEL 이력을 남긴다") {
                refunded shouldBe 400L
                point.amount shouldBe 10000L
                verify(exactly = 1) {
                    historyRepository.save(match<PointTransactionHistory> { it.transactionType == PointTransactionType.CANCEL })
                }
            }
        }
    }

    Given("이미 되돌린 sagaId") {
        val pointRepository = mockk<PointRepository>()
        val historyRepository = mockk<PointTransactionHistoryRepository>()
        val service = PointService(pointRepository, historyRepository, FIXED_CLOCK)
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
})
