package com.project.order.client

import com.project.common.exception.BusinessException
import com.project.order.client.dto.BuyApiRequest
import com.project.order.client.dto.BuyCancelApiRequest
import com.project.order.exception.ProductErrorCode
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.ExpectedCount
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess

private val BUY = BuyApiRequest("saga-1", 10L, listOf(BuyApiRequest.Item(1L, 2L)))

class ProductApiClientTest : BehaviorSpec({

    Given("재고 차감에 성공하는 Product 서버") {
        val s = ApiClientTestSupport()
        val client = ProductApiClient(s.restClient(), RemoteCallPolicy.LOCAL_WRITE, s.caller, s.objectMapper)
        s.server.expect(requestTo("http://remote/product/buy"))
            .andRespond(withSuccess("""{"totalPrice":400}""", MediaType.APPLICATION_JSON))

        When("재고 차감을 부르면") {
            val totalPrice = client.buy(BUY)

            Then("항목 총액을 돌려준다") {
                totalPrice shouldBe 400L
                s.server.verify()
            }
        }
    }

    Given("재고 부족으로 409를 내는 Product 서버") {
        val s = ApiClientTestSupport()
        val client = ProductApiClient(s.restClient(), RemoteCallPolicy.LOCAL_WRITE, s.caller, s.objectMapper)
        s.server.expect(ExpectedCount.once(), requestTo("http://remote/product/buy"))
            .andRespond(
                withStatus(HttpStatus.CONFLICT)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("""{"code":"INSUFFICIENT_STOCK","message":"재고가 부족합니다."}"""),
            )

        When("재고 차감을 부르면") {
            val exception = shouldThrow<BusinessException> { client.buy(BUY) }

            Then("INSUFFICIENT_STOCK으로 번역하고 재시도하지 않는다") {
                exception.errorCode shouldBe ProductErrorCode.INSUFFICIENT_STOCK
                s.server.verify()
            }
        }
    }

    Given("계속 500을 내는 Product 서버") {
        val s = ApiClientTestSupport()
        val client = ProductApiClient(s.restClient(), RemoteCallPolicy.LOCAL_WRITE, s.caller, s.objectMapper)
        s.server.expect(ExpectedCount.times(3), requestTo("http://remote/product/buy"))
            .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR))

        When("재고 차감을 부르면") {
            shouldThrow<Exception> { client.buy(BUY) }

            Then("5xx는 재시도 대상이라 세 번 시도한다") {
                s.server.verify()
            }
        }
    }

    Given("재고 복구에 성공하는 Product 서버") {
        val s = ApiClientTestSupport()
        val client = ProductApiClient(s.restClient(), RemoteCallPolicy.LOCAL_WRITE, s.caller, s.objectMapper)
        s.server.expect(requestTo("http://remote/product/buy/cancel"))
            .andRespond(withSuccess("""{"restoredPrice":400}""", MediaType.APPLICATION_JSON))

        When("보상을 부르면") {
            val restored = client.cancel(BuyCancelApiRequest("saga-1", 10L), RemoteCallPolicy.COMPENSATION_WORKER_ATTEMPTS)

            Then("복구 금액을 돌려준다") {
                restored shouldBe 400L
                s.server.verify()
            }
        }
    }
})
