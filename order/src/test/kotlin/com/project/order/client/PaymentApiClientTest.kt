package com.project.order.client

import com.project.common.exception.BusinessException
import com.project.order.client.dto.PayApiRequest
import com.project.order.client.dto.PayCancelApiRequest
import com.project.order.exception.PaymentErrorCode
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.ExpectedCount
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withException
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import java.net.SocketTimeoutException

private val PAY = PayApiRequest("saga-1", 10L, 1L, 400L)

class PaymentApiClientTest : BehaviorSpec({

    Given("결제에 성공하는 Payment 서버") {
        val s = ApiClientTestSupport()
        val client = PaymentApiClient(s.restClient(), RemoteCallPolicy.EXTERNAL_APPROVAL, s.caller, s.objectMapper)
        s.server.expect(requestTo("http://remote/payment"))
            .andRespond(withSuccess("""{"paymentId":7,"paidAt":"2026-09-22T12:00:00"}""", MediaType.APPLICATION_JSON))

        When("결제를 부르면") {
            val response = client.pay(PAY)

            Then("결제 식별자를 돌려준다") {
                response.paymentId shouldBe 7L
                s.server.verify()
            }
        }
    }

    Given("승인을 거절해 409를 내는 Payment 서버") {
        val s = ApiClientTestSupport()
        val client = PaymentApiClient(s.restClient(), RemoteCallPolicy.EXTERNAL_APPROVAL, s.caller, s.objectMapper)
        s.server.expect(ExpectedCount.once(), requestTo("http://remote/payment"))
            .andRespond(
                withStatus(HttpStatus.CONFLICT)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("""{"code":"PAYMENT_FAILED","message":"결제에 실패했습니다."}"""),
            )

        When("결제를 부르면") {
            val exception = shouldThrow<BusinessException> { client.pay(PAY) }

            Then("PAYMENT_FAILED로 번역하고 재시도하지 않는다") {
                exception.errorCode shouldBe PaymentErrorCode.PAYMENT_FAILED
                s.server.verify()
            }
        }
    }

    Given("계속 503을 내는 Payment 서버") {
        val s = ApiClientTestSupport()
        val client = PaymentApiClient(s.restClient(), RemoteCallPolicy.EXTERNAL_APPROVAL, s.caller, s.objectMapper)
        s.server.expect(ExpectedCount.times(2), requestTo("http://remote/payment"))
            .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE))

        When("결제를 부르면") {
            val exception = shouldThrow<BusinessException> { client.pay(PAY) }

            Then("두 번 시도한 뒤 PAYMENT_FAILED로 번역한다") {
                exception.errorCode shouldBe PaymentErrorCode.PAYMENT_FAILED
                s.server.verify()
            }
        }
    }

    Given("응답 없이 연결이 끊기는 Payment 서버") {
        val s = ApiClientTestSupport()
        val client = PaymentApiClient(s.restClient(), RemoteCallPolicy.EXTERNAL_APPROVAL, s.caller, s.objectMapper)
        s.server.expect(ExpectedCount.times(2), requestTo("http://remote/payment"))
            .andRespond(withException(SocketTimeoutException("Read timed out")))

        When("결제를 부르면") {
            val exception = shouldThrow<BusinessException> { client.pay(PAY) }

            Then("두 번 시도한 뒤 PAYMENT_FAILED로 번역한다") {
                exception.errorCode shouldBe PaymentErrorCode.PAYMENT_FAILED
                s.server.verify()
            }
        }
    }

    Given("결제 취소에 성공하는 Payment 서버") {
        val s = ApiClientTestSupport()
        val client = PaymentApiClient(s.restClient(), RemoteCallPolicy.EXTERNAL_APPROVAL, s.caller, s.objectMapper)
        s.server.expect(requestTo("http://remote/payment/cancel")).andRespond(withSuccess())

        When("보상을 부르면") {
            client.cancel(PayCancelApiRequest("saga-1", 10L), RemoteCallPolicy.COMPENSATION_WORKER_ATTEMPTS)

            Then("한 번 부르고 끝난다") {
                s.server.verify()
            }
        }
    }
})
