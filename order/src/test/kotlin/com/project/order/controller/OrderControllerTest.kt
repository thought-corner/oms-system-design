package com.project.order.controller

import com.project.common.exception.BusinessException
import com.project.common.exception.ErrorResponse
import com.project.order.controller.dto.CreateOrderResponse
import com.project.order.exception.OrderErrorCode
import com.project.order.service.OrderPlacementService
import com.project.order.service.OrderService
import com.project.order.service.dto.CreateOrderCommand
import com.project.order.service.dto.CreateOrderResult
import com.project.order.service.dto.OrderStatusResult
import com.project.order.service.dto.PlaceOrderCommand
import com.project.order.service.dto.PlaceOrderResult
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.core.test.isRootTest
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.mockk.Called
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.dao.CannotAcquireLockException
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import tools.jackson.databind.ObjectMapper

@WebMvcTest(OrderController::class)
@Import(OrderControllerTest.MockServices::class)
class OrderControllerTest : BehaviorSpec() {

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var objectMapper: ObjectMapper

    @Autowired
    lateinit var orderService: OrderService

    @Autowired
    lateinit var orderPlacementService: OrderPlacementService

    @TestConfiguration
    class MockServices {

        @Bean
        fun orderService(): OrderService = mockk()

        @Bean
        fun orderPlacementService(): OrderPlacementService = mockk()
    }

    private fun postJson(path: String, body: String): MockHttpServletResponse =
        mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().response

    private fun place(orderId: Long, key: String?): MockHttpServletResponse {
        val request = post("/order/place").contentType(MediaType.APPLICATION_JSON).content("""{"orderId":$orderId}""")
        key?.let { request.header("Idempotency-Key", it) }
        return mockMvc.perform(request).andReturn().response
    }

    private fun MockHttpServletResponse.asError(): ErrorResponse =
        objectMapper.readValue(contentAsString, ErrorResponse::class.java)

    init {
        extensions(SpringExtension())

        beforeContainer { testCase ->
            if (testCase.isRootTest()) {
                clearMocks(orderService, orderPlacementService)
            }
        }

        Given("userId가 빠진 주문 생성 요청") {
            val body = """{"orderItems":[{"productId":1,"quantity":1}]}"""

            When("POST /order를 보내면") {
                val response = postJson("/order", body)

                Then("400 MALFORMED_REQUEST이고 서비스를 부르지 않는다") {
                    response.status shouldBe HttpStatus.BAD_REQUEST.value()
                    response.asError().code shouldBe "MALFORMED_REQUEST"
                    verify { orderService wasNot Called }
                }
            }
        }

        Given("깨진 JSON 본문") {
            val body = """{"userId": 1, "orderItems": ["""

            When("POST /order를 보내면") {
                val response = postJson("/order", body)

                Then("400 MALFORMED_REQUEST이고 서비스를 부르지 않는다") {
                    response.status shouldBe HttpStatus.BAD_REQUEST.value()
                    response.asError().code shouldBe "MALFORMED_REQUEST"
                    verify { orderService wasNot Called }
                }
            }
        }

        Given("주문 항목이 비어 INVALID_ORDER를 던지는 서비스") {
            every {
                orderService.createOrder(CreateOrderCommand(1L, emptyList()))
            } throws BusinessException(OrderErrorCode.INVALID_ORDER, "orderItems is empty")

            When("항목 없는 주문 생성 요청을 보내면") {
                val response = postJson("/order", """{"userId":1,"orderItems":[]}""")

                Then("AC-10 400 INVALID_ORDER이고 detail이 메시지에 붙는다") {
                    response.status shouldBe HttpStatus.BAD_REQUEST.value()
                    val error = response.asError()
                    error.code shouldBe "INVALID_ORDER"
                    error.message shouldBe "주문 항목이 올바르지 않습니다. orderItems is empty"
                }
            }
        }

        Given("Idempotency-Key 없이는 INVALID_ORDER를 던지는 서비스") {
            every {
                orderPlacementService.place(PlaceOrderCommand(10L, null))
            } throws BusinessException(OrderErrorCode.INVALID_ORDER, "orderId=10, idempotencyKey=null")

            When("키 없이 결제를 요청하면") {
                val response = place(10L, null)

                Then("B-2 400 INVALID_ORDER") {
                    response.status shouldBe HttpStatus.BAD_REQUEST.value()
                    response.asError().code shouldBe "INVALID_ORDER"
                    response.getHeader("Location").shouldBeNull()
                }
            }
        }

        Given("주문 999가 없어 ORDER_NOT_FOUND를 던지는 서비스") {
            every { orderPlacementService.place(PlaceOrderCommand(999L, "key-1")) } throws
                BusinessException(OrderErrorCode.ORDER_NOT_FOUND, "orderId=999")

            When("주문 999 결제 요청을 보내면") {
                val response = place(999L, "key-1")

                Then("AC-7 404 ORDER_NOT_FOUND") {
                    response.status shouldBe HttpStatus.NOT_FOUND.value()
                    response.asError().code shouldBe "ORDER_NOT_FOUND"
                }
            }
        }

        Given("진행 중인 주문에 다른 키가 오면 전이를 거부하는 서비스") {
            every { orderPlacementService.place(PlaceOrderCommand(10L, "key-2")) } throws
                BusinessException(OrderErrorCode.INVALID_ORDER_STATE_TRANSITION, "orderId=10, status=PLACING, event=PLACE")

            When("다른 키로 결제를 요청하면") {
                val response = place(10L, "key-2")

                Then("B-2 409 INVALID_ORDER_STATE_TRANSITION") {
                    response.status shouldBe HttpStatus.CONFLICT.value()
                    response.asError().code shouldBe "INVALID_ORDER_STATE_TRANSITION"
                }
            }
        }

        Given("다른 트랜잭션이 주문을 잠가 락 예외를 던지는 서비스") {
            every { orderPlacementService.place(PlaceOrderCommand(10L, "key-1")) } throws CannotAcquireLockException("NOWAIT")

            When("주문 10 결제 요청을 보내면") {
                val response = place(10L, "key-1")

                Then("AC-6 409 ORDER_LOCKED이고 DB 메시지를 내보내지 않는다") {
                    response.status shouldBe HttpStatus.CONFLICT.value()
                    val error = response.asError()
                    error.code shouldBe "ORDER_LOCKED"
                    error.message shouldNotContain "NOWAIT"
                }
            }
        }

        Given("내부 정보가 담긴 IllegalStateException을 던지는 서비스") {
            every { orderPlacementService.place(PlaceOrderCommand(10L, "key-1")) } throws IllegalStateException("jdbc password 1234")

            When("주문 10 결제 요청을 보내면") {
                val response = place(10L, "key-1")

                Then("500 INTERNAL_ERROR이고 내부 메시지를 내보내지 않는다") {
                    response.status shouldBe HttpStatus.INTERNAL_SERVER_ERROR.value()
                    val error = response.asError()
                    error.code shouldBe "INTERNAL_ERROR"
                    error.message shouldNotContain "1234"
                }
            }
        }

        Given("결제를 받아 주는 서비스") {
            every { orderPlacementService.place(PlaceOrderCommand(10L, "key-1")) } returns PlaceOrderResult(10L)

            When("키와 함께 주문 10 결제를 요청하면") {
                val response = place(10L, "key-1")

                Then("B-1 202 에 Location 과 Retry-After 를 싣고 본문은 비운다") {
                    response.status shouldBe HttpStatus.ACCEPTED.value()
                    response.getHeader("Location") shouldBe "/order/10"
                    response.getHeader("Retry-After") shouldBe "1"
                    response.contentAsString shouldBe ""
                    verify(exactly = 1) { orderPlacementService.place(PlaceOrderCommand(10L, "key-1")) }
                }
            }
        }

        Given("진행 중인 주문") {
            every { orderService.findOrder(10L) } returns OrderStatusResult(10L, "PLACING", null, placing = true)

            When("GET /order/10 을 보내면") {
                val response = mockMvc.perform(get("/order/10")).andReturn().response

                Then("200 PLACING 에 Retry-After 가 붙고 code 는 없다") {
                    response.status shouldBe HttpStatus.OK.value()
                    response.getHeader("Retry-After") shouldBe "1"
                    val body = objectMapper.readTree(response.contentAsString)
                    body["orderId"].asLong() shouldBe 10L
                    body["status"].asString() shouldBe "PLACING"
                    body.has("code") shouldBe false
                }
            }
        }

        Given("잔액 부족으로 보상 중인 주문") {
            every { orderService.findOrder(10L) } returns OrderStatusResult(10L, "PLACING", "INSUFFICIENT_POINT", placing = true)

            When("GET /order/10 을 보내면") {
                val response = mockMvc.perform(get("/order/10")).andReturn().response

                Then("B-3 PLACING 과 실패 code 를 함께 준다") {
                    response.getHeader("Retry-After") shouldBe "1"
                    objectMapper.readTree(response.contentAsString)["code"].asString() shouldBe "INSUFFICIENT_POINT"
                }
            }
        }

        Given("보상까지 끝난 주문") {
            every { orderService.findOrder(10L) } returns OrderStatusResult(10L, "FAILED", "INSUFFICIENT_POINT", placing = false)

            When("GET /order/10 을 보내면") {
                val response = mockMvc.perform(get("/order/10")).andReturn().response

                Then("200 FAILED 와 code 이고 Retry-After 는 없다") {
                    response.status shouldBe HttpStatus.OK.value()
                    response.getHeader("Retry-After").shouldBeNull()
                    val body = objectMapper.readTree(response.contentAsString)
                    body["status"].asString() shouldBe "FAILED"
                    body["code"].asString() shouldBe "INSUFFICIENT_POINT"
                }
            }
        }

        Given("없는 주문 999") {
            every { orderService.findOrder(999L) } throws BusinessException(OrderErrorCode.ORDER_NOT_FOUND, "orderId=999")

            When("GET /order/999 를 보내면") {
                val response = mockMvc.perform(get("/order/999")).andReturn().response

                Then("AC-7 404 ORDER_NOT_FOUND") {
                    response.status shouldBe HttpStatus.NOT_FOUND.value()
                    response.asError().code shouldBe "ORDER_NOT_FOUND"
                }
            }
        }

        Given("숫자가 아닌 주문 id") {

            When("GET /order/abc 를 보내면") {
                val response = mockMvc.perform(get("/order/abc")).andReturn().response

                Then("400 INVALID_PARAMETER이고 서비스를 부르지 않는다") {
                    response.status shouldBe HttpStatus.BAD_REQUEST.value()
                    response.asError().code shouldBe "INVALID_PARAMETER"
                    verify { orderService wasNot Called }
                }
            }
        }

        Given("지원하지 않는 메서드") {

            When("GET /order를 보내면") {
                val response = mockMvc.perform(get("/order")).andReturn().response

                Then("405 METHOD_NOT_ALLOWED이고 Allow 헤더는 POST") {
                    response.status shouldBe HttpStatus.METHOD_NOT_ALLOWED.value()
                    response.asError().code shouldBe "METHOD_NOT_ALLOWED"
                    response.getHeader("Allow") shouldBe "POST"
                    verify { orderService wasNot Called }
                }
            }
        }

        Given("JSON을 받지 않는 클라이언트") {
            every {
                orderService.createOrder(CreateOrderCommand(2L, listOf(CreateOrderCommand.OrderItem(1L, 2L))))
            } returns CreateOrderResult(5L)

            When("Accept: text/plain으로 POST /order를 보내면") {
                val response = mockMvc.perform(
                    post("/order").contentType(MediaType.APPLICATION_JSON).accept(MediaType.TEXT_PLAIN)
                        .content("""{"userId":2,"orderItems":[{"productId":1,"quantity":2}]}"""),
                ).andReturn().response

                Then("406이고 본문이 없다") {
                    response.status shouldBe HttpStatus.NOT_ACCEPTABLE.value()
                    response.contentAsString shouldBe ""
                }
            }
        }

        Given("주문 5를 만들어 주는 서비스") {
            every {
                orderService.createOrder(CreateOrderCommand(2L, listOf(CreateOrderCommand.OrderItem(1L, 2L))))
            } returns CreateOrderResult(5L)

            When("userId 2가 상품 1을 2개 주문하면") {
                val response = postJson("/order", """{"userId":2,"orderItems":[{"productId":1,"quantity":2}]}""")

                Then("AC-1 200이고 orderId 5를 돌려준다") {
                    response.status shouldBe HttpStatus.OK.value()
                    objectMapper.readValue(response.contentAsString, CreateOrderResponse::class.java).orderId shouldBe 5L
                }
            }
        }
    }
}
