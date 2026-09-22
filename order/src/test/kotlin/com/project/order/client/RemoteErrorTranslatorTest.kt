package com.project.order.client

import com.project.order.exception.ProductErrorCode
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
})
