package com.project.point.service

import com.project.common.exception.BusinessException
import com.project.point.domain.SagaGuardKind
import com.project.point.exception.PointErrorCode
import com.project.point.fixture.PointFixture
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.verifyOrder

class SagaGuardLockTest : BehaviorSpec({

    Given("가드가 없는 sagaId") {
        val guardRepository = PointFixture.guardRepository(SagaGuardKind.FORWARD)
        val sagaGuardLock = SagaGuardLock(guardRepository, PointFixture.FIXED_CLOCK)

        When("사용 쪽이 가드를 잡으면") {
            shouldNotThrowAny { sagaGuardLock.lockForward("saga-1") }

            Then("FORWARD 가드를 넣은 뒤 그 행을 잠근다") {
                verifyOrder {
                    guardRepository.insertIfAbsent("saga-1", SagaGuardKind.FORWARD.name, any())
                    guardRepository.findWithLockBySagaId("saga-1")
                }
            }
        }
    }

    Given("보상이 먼저 도착해 CANCEL 가드가 남은 sagaId") {
        val guardRepository = PointFixture.guardRepository(SagaGuardKind.CANCEL)
        val sagaGuardLock = SagaGuardLock(guardRepository, PointFixture.FIXED_CLOCK)

        When("늦게 도착한 사용 쪽이 가드를 잡으면") {
            val exception = shouldThrow<BusinessException> { sagaGuardLock.lockForward("saga-1") }

            Then("SAGA_ALREADY_COMPENSATED로 거부한다") {
                exception.errorCode shouldBe PointErrorCode.SAGA_ALREADY_COMPENSATED
            }
        }

        When("보상 쪽이 가드를 잡으면") {
            shouldNotThrowAny { sagaGuardLock.lockCancel("saga-1") }

            Then("CANCEL 가드를 넣은 뒤 그 행을 잠근다") {
                verifyOrder {
                    guardRepository.insertIfAbsent("saga-1", SagaGuardKind.CANCEL.name, any())
                    guardRepository.findWithLockBySagaId("saga-1")
                }
            }
        }
    }
})
