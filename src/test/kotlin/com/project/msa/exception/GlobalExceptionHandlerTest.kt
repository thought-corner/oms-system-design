package com.project.msa.exception

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.springframework.dao.CannotAcquireLockException
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.mock.http.MockHttpInputMessage
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.server.ResponseStatusException

class GlobalExceptionHandlerTest : BehaviorSpec({

    val handler = GlobalExceptionHandler()

    Given("BusinessException") {
        val cases = listOf(
            OrderErrorCode.ORDER_NOT_FOUND to HttpStatus.NOT_FOUND,
            OrderErrorCode.INVALID_ORDER to HttpStatus.BAD_REQUEST,
            OrderErrorCode.INVALID_ORDER_STATE_TRANSITION to HttpStatus.CONFLICT,
            ProductErrorCode.INSUFFICIENT_STOCK to HttpStatus.CONFLICT,
            PointErrorCode.INSUFFICIENT_POINT to HttpStatus.CONFLICT,
        )

        cases.forEach { (errorCode, status) ->
            When("errorCode 가 ${errorCode.code} 면") {
                val response = handler.handleBusiness(BusinessException(errorCode))

                Then("AC-11 ${status.value()} 이고 code 는 enum 이름") {
                    response.statusCode shouldBe status
                    response.body?.code shouldBe errorCode.code
                    response.body?.message shouldBe errorCode.message
                }
            }
        }

        When("detail 이 있으면") {
            val response = handler.handleBusiness(BusinessException(ProductErrorCode.PRODUCT_NOT_FOUND, "productId=9"))

            Then("메시지 뒤에 붙는다") {
                response.statusCode shouldBe HttpStatus.NOT_FOUND
                response.body?.message shouldBe "상품이 존재하지 않습니다. productId=9"
            }
        }
    }

    Given("행 락 경합 예외") {

        When("번역하면") {
            val response = handler.handleLock(CannotAcquireLockException("NOWAIT"))

            Then("409 ORDER_LOCKED") {
                response.statusCode shouldBe HttpStatus.CONFLICT
                response.body?.code shouldBe "ORDER_LOCKED"
                response.body?.message shouldNotContain "NOWAIT"
            }
        }
    }

    Given("본문을 읽을 수 없는 예외") {

        When("번역하면") {
            val response = handler.handleUnreadable(
                HttpMessageNotReadableException("JSON parse error", MockHttpInputMessage(ByteArray(0))),
            )

            Then("400 MALFORMED_REQUEST") {
                response.statusCode shouldBe HttpStatus.BAD_REQUEST
                response.body?.code shouldBe "MALFORMED_REQUEST"
                response.body?.message shouldNotContain "JSON parse error"
            }
        }
    }

    Given("예상 못 한 예외") {

        When("내부 정보가 담긴 IllegalStateException 이면") {
            val response = handler.handleUnexpected(IllegalStateException("DB 비밀번호는 1234"))

            Then("500 INTERNAL_ERROR 이고 내부 메시지를 내보내지 않는다") {
                response.statusCode shouldBe HttpStatus.INTERNAL_SERVER_ERROR
                response.body?.code shouldBe "INTERNAL_ERROR"
                response.body?.message shouldNotContain "1234"
            }
        }

        When("IllegalArgumentException 이면") {
            val response = handler.handleUnexpected(IllegalArgumentException("Required value was null."))

            Then("클라이언트 탓이 아니므로 500") {
                response.statusCode shouldBe HttpStatus.INTERNAL_SERVER_ERROR
                response.body?.code shouldBe "INTERNAL_ERROR"
            }
        }

        When("Spring 이 상태를 아는 HttpRequestMethodNotSupportedException 이면") {
            val response = handler.handleUnexpected(HttpRequestMethodNotSupportedException("GET", listOf("POST")))

            Then("405 HTTP_405 이고 Allow 헤더를 유지한다") {
                response.statusCode shouldBe HttpStatus.METHOD_NOT_ALLOWED
                response.body?.code shouldBe "HTTP_405"
                response.headers.allow.toList() shouldBe listOf(HttpMethod.POST)
            }
        }

        When("비표준 상태 코드 499 인 ResponseStatusException 이면") {
            val response = handler.handleUnexpected(ResponseStatusException(HttpStatusCode.valueOf(499)))

            Then("499 HTTP_499 를 그대로 내보낸다") {
                response.statusCode.value() shouldBe 499
                response.body?.code shouldBe "HTTP_499"
            }
        }
    }
})
