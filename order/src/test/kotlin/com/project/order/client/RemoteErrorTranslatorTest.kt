package com.project.order.client

import com.project.common.exception.BusinessException
import com.project.order.exception.ProductErrorCode
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.springframework.http.HttpStatus
import org.springframework.web.client.RestClientResponseException
import tools.jackson.databind.ObjectMapper

private fun response(status: HttpStatus, body: String) =
    RestClientResponseException(
        "remote failed",
        status.value(),
        status.reasonPhrase,
        null,
        body.toByteArray(),
        null,
    )

class RemoteErrorTranslatorTest : BehaviorSpec({

    val translator = RemoteErrorTranslator(
        ObjectMapper(),
        ProductErrorCode.entries.associateBy { it.code },
        ProductErrorCode.PRODUCT_NOT_FOUND,
    )

    Given("상대가 아는 코드를 돌려준 응답") {

        When("번역하면") {
            val exception = translator.translate(
                response(HttpStatus.CONFLICT, """{"code":"INSUFFICIENT_STOCK","message":"재고가 부족합니다."}"""),
            )

            Then("우리 쪽 같은 이름의 에러 코드로 옮기고 원격 정보를 detail에 남긴다") {
                exception.errorCode shouldBe ProductErrorCode.INSUFFICIENT_STOCK
                exception.message shouldContain "remoteStatus=409"
                exception.message shouldContain "remoteCode=INSUFFICIENT_STOCK"
            }
        }
    }

    Given("보상이 먼저 도착해 차감을 거부한 응답") {

        When("번역하면") {
            val exception = translator.translate(
                response(HttpStatus.CONFLICT, """{"code":"SAGA_ALREADY_COMPENSATED","message":"이미 보상된 사가입니다."}"""),
            )

            Then("폴백이 아니라 SAGA_ALREADY_COMPENSATED로 옮긴다") {
                exception.errorCode shouldBe ProductErrorCode.SAGA_ALREADY_COMPENSATED
            }
        }
    }

    Given("모르는 코드를 돌려준 응답") {

        When("번역하면") {
            val exception = translator.translate(
                response(HttpStatus.CONFLICT, """{"code":"SOMETHING_ELSE","message":"?"}"""),
            )

            Then("기본 코드로 떨어뜨리되 원격 코드를 남겨 추적할 수 있게 한다") {
                exception.errorCode shouldBe ProductErrorCode.PRODUCT_NOT_FOUND
                exception.message shouldContain "remoteCode=SOMETHING_ELSE"
            }
        }
    }

    Given("본문이 JSON이 아닌 응답") {

        When("번역하면") {
            val exception = translator.translate(response(HttpStatus.BAD_REQUEST, "<html>500</html>"))

            Then("파싱 실패로 죽지 않고 기본 코드로 떨어진다") {
                exception.errorCode shouldBe ProductErrorCode.PRODUCT_NOT_FOUND
                exception.message shouldContain "remoteCode=null"
            }
        }
    }

    Given("본문이 빈 응답") {

        When("번역하면") {
            val exception = translator.translate(response(HttpStatus.NOT_FOUND, ""))

            Then("기본 코드로 떨어진다") {
                exception.errorCode shouldBe ProductErrorCode.PRODUCT_NOT_FOUND
            }
        }
    }

    Given("원격 호출이 4xx로 실패하는 블록") {
        val failure = response(HttpStatus.CONFLICT, """{"code":"INSUFFICIENT_STOCK","message":"재고가 부족합니다."}""")

        When("번역하며 실행하면") {
            val exception = shouldThrow<BusinessException> { translator.translating { throw failure } }

            Then("우리 쪽 에러 코드의 BusinessException으로 바꿔 재시도를 끊는다") {
                exception.errorCode shouldBe ProductErrorCode.INSUFFICIENT_STOCK
            }
        }
    }

    Given("원격 호출이 5xx로 실패하는 블록") {
        val failure = response(HttpStatus.SERVICE_UNAVAILABLE, "")

        When("번역하며 실행하면") {
            val exception = shouldThrow<RestClientResponseException> { translator.translating { throw failure } }

            Then("번역하지 않고 그대로 던져 재시도 대상으로 남긴다") {
                exception shouldBe failure
            }
        }
    }

    Given("원격 호출이 성공하는 블록") {

        When("번역하며 실행하면") {
            val result = translator.translating { 400L }

            Then("블록의 결과를 그대로 돌려준다") {
                result shouldBe 400L
            }
        }
    }
})
