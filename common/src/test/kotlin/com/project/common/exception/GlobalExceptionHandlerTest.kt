package com.project.common.exception

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.springframework.core.MethodParameter
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.mock.http.MockHttpInputMessage
import org.springframework.web.HttpMediaTypeNotAcceptableException
import org.springframework.web.HttpMediaTypeNotSupportedException
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.servlet.resource.NoResourceFoundException

class GlobalExceptionHandlerTest : BehaviorSpec({

    val handler = GlobalExceptionHandler()

    Given("BusinessException") {
        val cases = listOf(
            TestErrorCode.NOT_FOUND to HttpStatus.NOT_FOUND,
            TestErrorCode.BAD_REQUEST to HttpStatus.BAD_REQUEST,
            TestErrorCode.CONFLICT to HttpStatus.CONFLICT,
        )

        cases.forEach { (errorCode, status) ->
            When("errorCode가 ${errorCode.code} 면") {
                val response = handler.handleBusiness(BusinessException(errorCode))

                Then("AC-11 ${status.value()} 이고 code는 enum 이름") {
                    response.statusCode shouldBe status
                    response.body?.code shouldBe errorCode.code
                    response.body?.message shouldBe errorCode.message
                }
            }
        }

        When("detail이 있으면") {
            val response = handler.handleBusiness(BusinessException(TestErrorCode.NOT_FOUND, "productId=9"))

            Then("메시지 뒤에 붙는다") {
                response.statusCode shouldBe HttpStatus.NOT_FOUND
                response.body?.message shouldBe "없습니다. productId=9"
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
                response.body?.code shouldBe CommonErrorCode.MALFORMED_REQUEST.code
                response.body?.message shouldNotContain "JSON parse error"
            }
        }
    }

    Given("예상 못 한 예외") {

        When("내부 정보가 담긴 IllegalStateException이면") {
            val response = handler.handleUnexpected(IllegalStateException("DB 비밀번호는 1234"))

            Then("500 INTERNAL_ERROR이고 내부 메시지를 내보내지 않는다") {
                response.statusCode shouldBe HttpStatus.INTERNAL_SERVER_ERROR
                response.body?.code shouldBe CommonErrorCode.INTERNAL_ERROR.code
                response.body?.message shouldNotContain "1234"
            }
        }

        When("IllegalArgumentException이면") {
            val response = handler.handleUnexpected(IllegalArgumentException("Required value was null."))

            Then("클라이언트 탓이 아니므로 500") {
                response.statusCode shouldBe HttpStatus.INTERNAL_SERVER_ERROR
                response.body?.code shouldBe CommonErrorCode.INTERNAL_ERROR.code
            }
        }
    }

    Given("Spring MVC가 요청을 거절한 예외") {

        When("HttpRequestMethodNotSupportedException이면") {
            val response = handler.handleMethodNotSupported(HttpRequestMethodNotSupportedException("GET", listOf("POST")))

            Then("405 METHOD_NOT_ALLOWED이고 Allow 헤더를 유지한다") {
                response.statusCode shouldBe HttpStatus.METHOD_NOT_ALLOWED
                response.body?.code shouldBe CommonErrorCode.METHOD_NOT_ALLOWED.code
                response.headers.allow.toList() shouldBe listOf(HttpMethod.POST)
            }
        }

        When("HttpMediaTypeNotSupportedException이면") {
            val response = handler.handleMediaTypeNotSupported(
                HttpMediaTypeNotSupportedException(MediaType.TEXT_PLAIN, listOf(MediaType.APPLICATION_JSON)),
            )

            Then("415 UNSUPPORTED_MEDIA_TYPE이고 Accept 헤더를 유지한다") {
                response.statusCode shouldBe HttpStatus.UNSUPPORTED_MEDIA_TYPE
                response.body?.code shouldBe CommonErrorCode.UNSUPPORTED_MEDIA_TYPE.code
                response.headers.accept shouldBe listOf(MediaType.APPLICATION_JSON)
            }
        }

        When("HttpMediaTypeNotAcceptableException이면") {
            val response = handler.handleMediaTypeNotAcceptable(HttpMediaTypeNotAcceptableException(listOf(MediaType.APPLICATION_JSON)))

            Then("406이고 클라이언트가 받지 않는 JSON 본문은 싣지 않는다") {
                response.statusCode shouldBe HttpStatus.NOT_ACCEPTABLE
                response.body shouldBe null
            }
        }

        When("NoResourceFoundException이면") {
            val response = handler.handleNoResource(NoResourceFoundException(HttpMethod.POST, "/nowhere", "nowhere"))

            Then("404 RESOURCE_NOT_FOUND") {
                response.statusCode shouldBe HttpStatus.NOT_FOUND
                response.body?.code shouldBe CommonErrorCode.RESOURCE_NOT_FOUND.code
            }
        }

        When("경로 변수를 Long 으로 바꾸지 못한 MethodArgumentTypeMismatchException이면") {
            val response = handler.handleTypeMismatch(
                MethodArgumentTypeMismatchException(
                    "abc",
                    Long::class.java,
                    "orderId",
                    MethodParameter.forExecutable(String::class.java.getMethod("charAt", Int::class.javaPrimitiveType), 0),
                    NumberFormatException("For input string: \"abc\""),
                ),
            )

            Then("400 INVALID_PARAMETER이고 변환 예외 메시지를 내보내지 않는다") {
                response.statusCode shouldBe HttpStatus.BAD_REQUEST
                response.body?.code shouldBe CommonErrorCode.INVALID_PARAMETER.code
                response.body?.message shouldNotContain "abc"
            }
        }
    }
})
