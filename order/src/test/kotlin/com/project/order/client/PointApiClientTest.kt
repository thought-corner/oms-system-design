package com.project.order.client

import com.project.common.exception.BusinessException
import com.project.order.client.dto.UseApiRequest
import com.project.order.client.dto.UseCancelApiRequest
import com.project.order.exception.PointErrorCode
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.ExpectedCount
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess

private val USE = UseApiRequest("saga-1", 10L, 1L, 400L)

class PointApiClientTest : BehaviorSpec({

    Given("포인트 사용에 성공하는 Point 서버") {
        val s = ApiClientTestSupport()
        val client = PointApiClient(s.restClient(), RemoteCallPolicy.LOCAL_WRITE, s.caller, s.objectMapper)
        s.server.expect(requestTo("http://remote/point/use")).andRespond(withSuccess())

        When("포인트 사용을 부르면") {
            client.use(USE)

            Then("한 번 부르고 끝난다") {
                s.server.verify()
            }
        }
    }

    Given("잔액 부족으로 409를 내는 Point 서버") {
        val s = ApiClientTestSupport()
        val client = PointApiClient(s.restClient(), RemoteCallPolicy.LOCAL_WRITE, s.caller, s.objectMapper)
        s.server.expect(ExpectedCount.once(), requestTo("http://remote/point/use"))
            .andRespond(
                withStatus(HttpStatus.CONFLICT)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("""{"code":"INSUFFICIENT_POINT","message":"잔액이 부족합니다."}"""),
            )

        When("포인트 사용을 부르면") {
            val exception = shouldThrow<BusinessException> { client.use(USE) }

            Then("INSUFFICIENT_POINT로 번역하고 재시도하지 않는다") {
                exception.errorCode shouldBe PointErrorCode.INSUFFICIENT_POINT
                s.server.verify()
            }
        }
    }

    Given("포인트가 없어 404를 내는 Point 서버") {
        val s = ApiClientTestSupport()
        val client = PointApiClient(s.restClient(), RemoteCallPolicy.LOCAL_WRITE, s.caller, s.objectMapper)
        s.server.expect(ExpectedCount.once(), requestTo("http://remote/point/use"))
            .andRespond(
                withStatus(HttpStatus.NOT_FOUND)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("""{"code":"POINT_NOT_FOUND","message":"포인트가 존재하지 않습니다."}"""),
            )

        When("포인트 사용을 부르면") {
            val exception = shouldThrow<BusinessException> { client.use(USE) }

            Then("POINT_NOT_FOUND로 번역한다") {
                exception.errorCode shouldBe PointErrorCode.POINT_NOT_FOUND
            }
        }
    }

    Given("포인트 환불에 성공하는 Point 서버") {
        val s = ApiClientTestSupport()
        val client = PointApiClient(s.restClient(), RemoteCallPolicy.LOCAL_WRITE, s.caller, s.objectMapper)
        s.server.expect(requestTo("http://remote/point/use/cancel"))
            .andRespond(withSuccess("""{"refundedAmount":400}""", MediaType.APPLICATION_JSON))

        When("보상을 부르면") {
            val refunded = client.cancel(UseCancelApiRequest("saga-1", 10L), RemoteCallPolicy.COMPENSATION_WORKER_ATTEMPTS)

            Then("환불 금액을 돌려준다") {
                refunded shouldBe 400L
                s.server.verify()
            }
        }
    }
})
