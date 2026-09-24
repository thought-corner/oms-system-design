package com.project.product.service

import com.project.common.exception.BusinessException
import com.project.product.domain.SagaGuardKind
import com.project.product.exception.ProductErrorCode
import com.project.product.fixture.ProductFixture
import com.project.product.repository.SagaGuardRepository
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verifyOrder
import java.time.Clock
import java.time.ZoneId

private val FIXED_CLOCK: Clock =
    Clock.fixed(ProductFixture.SEED_TIME.atZone(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault())

private fun guardRepository(lockedKind: SagaGuardKind): SagaGuardRepository =
    mockk<SagaGuardRepository>().also {
        every { it.insertIfAbsent(any(), any(), any()) } just Runs
        every { it.findWithLockBySagaId(any()) } answers { ProductFixture.guard(sagaId = firstArg(), kind = lockedKind) }
    }

class SagaGuardLockTest : BehaviorSpec({

    Given("가드가 없는 sagaId") {
        val guardRepository = guardRepository(SagaGuardKind.FORWARD)
        val sagaGuardLock = SagaGuardLock(guardRepository, FIXED_CLOCK)

        When("차감 쪽이 가드를 잡으면") {
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
        val guardRepository = guardRepository(SagaGuardKind.CANCEL)
        val sagaGuardLock = SagaGuardLock(guardRepository, FIXED_CLOCK)

        When("늦게 도착한 차감 쪽이 가드를 잡으면") {
            val exception = shouldThrow<BusinessException> { sagaGuardLock.lockForward("saga-1") }

            Then("SAGA_ALREADY_COMPENSATED로 거부한다") {
                exception.errorCode shouldBe ProductErrorCode.SAGA_ALREADY_COMPENSATED
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
