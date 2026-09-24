package com.project.payment.service

import com.project.common.exception.BusinessException
import com.project.payment.domain.SagaGuardKind
import com.project.payment.exception.PaymentErrorCode
import com.project.payment.fixture.PaymentFixture
import com.project.payment.repository.SagaGuardRepository
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verifyOrder

private fun guardRepository(lockedKind: SagaGuardKind): SagaGuardRepository =
    mockk<SagaGuardRepository>().also {
        every { it.insertIfAbsent(any(), any(), any()) } just Runs
        every { it.findWithLockBySagaId(any()) } answers { PaymentFixture.guard(sagaId = firstArg(), kind = lockedKind) }
    }

class SagaGuardLockTest : BehaviorSpec({

    Given("가드가 없는 sagaId") {
        val guardRepository = guardRepository(SagaGuardKind.FORWARD)
        val sagaGuardLock = SagaGuardLock(guardRepository, PaymentFixture.FIXED_CLOCK)

        When("결제 쪽이 가드를 잡으면") {
            shouldNotThrowAny { sagaGuardLock.lockForward(PaymentFixture.DEFAULT_SAGA_ID) }

            Then("FORWARD 가드를 넣은 뒤 그 행을 잠근다") {
                verifyOrder {
                    guardRepository.insertIfAbsent(PaymentFixture.DEFAULT_SAGA_ID, SagaGuardKind.FORWARD.name, any())
                    guardRepository.findWithLockBySagaId(PaymentFixture.DEFAULT_SAGA_ID)
                }
            }
        }
    }

    Given("보상이 먼저 도착해 CANCEL 가드가 남은 sagaId") {
        val guardRepository = guardRepository(SagaGuardKind.CANCEL)
        val sagaGuardLock = SagaGuardLock(guardRepository, PaymentFixture.FIXED_CLOCK)

        When("늦게 도착한 결제 쪽이 가드를 잡으면") {
            val exception = shouldThrow<BusinessException> { sagaGuardLock.lockForward(PaymentFixture.DEFAULT_SAGA_ID) }

            Then("SAGA_ALREADY_COMPENSATED로 거부한다") {
                exception.errorCode shouldBe PaymentErrorCode.SAGA_ALREADY_COMPENSATED
            }
        }

        When("보상 쪽이 가드를 잡으면") {
            shouldNotThrowAny { sagaGuardLock.lockCancel(PaymentFixture.DEFAULT_SAGA_ID) }

            Then("CANCEL 가드를 넣은 뒤 그 행을 잠근다") {
                verifyOrder {
                    guardRepository.insertIfAbsent(PaymentFixture.DEFAULT_SAGA_ID, SagaGuardKind.CANCEL.name, any())
                    guardRepository.findWithLockBySagaId(PaymentFixture.DEFAULT_SAGA_ID)
                }
            }
        }
    }
})
