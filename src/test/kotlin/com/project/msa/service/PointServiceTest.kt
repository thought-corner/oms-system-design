package com.project.msa.service

import com.project.msa.exception.BusinessException
import com.project.msa.exception.PointErrorCode
import com.project.msa.fixture.PointFixture
import com.project.msa.repository.PointRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify

class PointServiceTest : BehaviorSpec({

    Given("포인트가 없는 사용자 99") {
        val pointRepository = mockk<PointRepository>()
        val service = PointService(pointRepository)
        every { pointRepository.findWithLockByUserId(99L) } returns null

        When("포인트를 쓰면") {
            val exception = shouldThrow<BusinessException> { service.use(99L, 1L) }

            Then("AC-9 POINT_NOT_FOUND") {
                exception.errorCode shouldBe PointErrorCode.POINT_NOT_FOUND
                exception.message shouldContain "userId=99"
            }
        }
    }

    Given("잔액 399 인 사용자 1") {
        val pointRepository = mockk<PointRepository>()
        val service = PointService(pointRepository)
        val point = PointFixture.point(amount = 399L)
        every { pointRepository.findWithLockByUserId(1L) } returns point

        When("400 을 쓰면") {
            val exception = shouldThrow<BusinessException> { service.use(1L, 400L) }

            Then("INSUFFICIENT_POINT 이고 잔액은 그대로") {
                exception.errorCode shouldBe PointErrorCode.INSUFFICIENT_POINT
                point.amount shouldBe 399L
            }
        }
    }

    Given("잔액 10000 인 사용자 1") {
        val pointRepository = mockk<PointRepository>()
        val service = PointService(pointRepository)
        val point = PointFixture.point()
        every { pointRepository.findWithLockByUserId(1L) } returns point

        When("400 을 쓰면") {
            service.use(1L, 400L)

            Then("행 락으로 읽어 잔액을 차감한다") {
                point.amount shouldBe 9600L
                verify(exactly = 1) { pointRepository.findWithLockByUserId(1L) }
                verify(exactly = 0) { pointRepository.findById(any()) }
            }
        }
    }
})
