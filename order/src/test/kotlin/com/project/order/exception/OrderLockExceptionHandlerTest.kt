package com.project.order.exception

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.springframework.dao.CannotAcquireLockException
import org.springframework.http.HttpStatus

class OrderLockExceptionHandlerTest : BehaviorSpec({

    Given("행 락 경합 예외") {
        val handler = OrderLockExceptionHandler()

        When("번역하면") {
            val response = handler.handleLock(CannotAcquireLockException("NOWAIT"))

            Then("409 ORDER_LOCKED이고 DB 메시지를 내보내지 않는다") {
                response.statusCode shouldBe HttpStatus.CONFLICT
                response.body?.code shouldBe "ORDER_LOCKED"
                response.body?.message shouldNotContain "NOWAIT"
            }
        }
    }
})
