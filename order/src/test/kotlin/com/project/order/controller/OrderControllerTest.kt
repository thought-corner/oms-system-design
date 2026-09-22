package com.project.order.controller

import com.project.order.controller.dto.CreateOrderResponse
import com.project.common.exception.BusinessException
import com.project.common.exception.ErrorResponse
import com.project.order.exception.OrderErrorCode
import com.project.order.exception.ProductErrorCode
import com.project.order.service.OrderService
import com.project.order.service.SagaOrchestrator
import com.project.order.service.dto.CreateOrderCommand
import com.project.order.service.dto.CreateOrderResult
import com.project.order.service.dto.PlaceOrderCommand
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.core.test.isRootTest
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.mockk.Called
import io.mockk.Runs
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.just
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
@Import(OrderControllerTest.MockOrderService::class)
class OrderControllerTest : BehaviorSpec() {

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var objectMapper: ObjectMapper

    @Autowired
    lateinit var orderService: OrderService

    @Autowired
    lateinit var sagaOrchestrator: SagaOrchestrator

    @TestConfiguration
    class MockOrderService {

        @Bean
        fun orderService(): OrderService = mockk()

        @Bean
        fun sagaOrchestrator(): SagaOrchestrator = mockk()
    }

    private fun postJson(path: String, body: String): MockHttpServletResponse =
        mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().response

    private fun MockHttpServletResponse.asError(): ErrorResponse =
        objectMapper.readValue(contentAsString, ErrorResponse::class.java)

    init {
        extensions(SpringExtension())

        beforeContainer { testCase ->
            if (testCase.isRootTest()) {
                clearMocks(orderService, sagaOrchestrator)
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

        Given("주문 999가 없어 ORDER_NOT_FOUND를 던지는 서비스") {
            every {
                sagaOrchestrator.placeOrder(PlaceOrderCommand(999L))
            } throws BusinessException(OrderErrorCode.ORDER_NOT_FOUND, "orderId=999")

            When("주문 999 결제 요청을 보내면") {
                val response = postJson("/order/place", """{"orderId":999}""")

                Then("AC-7 404 ORDER_NOT_FOUND") {
                    response.status shouldBe HttpStatus.NOT_FOUND.value()
                    response.asError().code shouldBe "ORDER_NOT_FOUND"
                }
            }
        }

        Given("재고 부족으로 INSUFFICIENT_STOCK을 던지는 서비스") {
            every {
                sagaOrchestrator.placeOrder(PlaceOrderCommand(10L))
            } throws BusinessException(ProductErrorCode.INSUFFICIENT_STOCK)

            When("주문 10 결제 요청을 보내면") {
                val response = postJson("/order/place", """{"orderId":10}""")

                Then("AC-3 409 INSUFFICIENT_STOCK") {
                    response.status shouldBe HttpStatus.CONFLICT.value()
                    response.asError().code shouldBe "INSUFFICIENT_STOCK"
                }
            }
        }

        Given("다른 트랜잭션이 주문을 잠가 락 예외를 던지는 서비스") {
            every { sagaOrchestrator.placeOrder(PlaceOrderCommand(10L)) } throws CannotAcquireLockException("NOWAIT")

            When("주문 10 결제 요청을 보내면") {
                val response = postJson("/order/place", """{"orderId":10}""")

                Then("AC-6 409 ORDER_LOCKED이고 DB 메시지를 내보내지 않는다") {
                    response.status shouldBe HttpStatus.CONFLICT.value()
                    val error = response.asError()
                    error.code shouldBe "ORDER_LOCKED"
                    error.message shouldNotContain "NOWAIT"
                }
            }
        }

        Given("내부 정보가 담긴 IllegalStateException을 던지는 서비스") {
            every { sagaOrchestrator.placeOrder(PlaceOrderCommand(10L)) } throws IllegalStateException("jdbc password 1234")

            When("주문 10 결제 요청을 보내면") {
                val response = postJson("/order/place", """{"orderId":10}""")

                Then("500 INTERNAL_ERROR이고 내부 메시지를 내보내지 않는다") {
                    response.status shouldBe HttpStatus.INTERNAL_SERVER_ERROR.value()
                    val error = response.asError()
                    error.code shouldBe "INTERNAL_ERROR"
                    error.message shouldBe "서버 내부 오류입니다."
                    error.message shouldNotContain "1234"
                }
            }
        }

        Given("지원하지 않는 메서드") {

            When("GET /order를 보내면") {
                val response = mockMvc.perform(get("/order")).andReturn().response

                Then("405 HTTP_405이고 Allow 헤더는 POST") {
                    response.status shouldBe HttpStatus.METHOD_NOT_ALLOWED.value()
                    response.asError().code shouldBe "HTTP_405"
                    response.getHeader("Allow") shouldBe "POST"
                    verify { orderService wasNot Called }
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

        Given("결제를 정상 처리하는 서비스") {
            every { sagaOrchestrator.placeOrder(PlaceOrderCommand(10L)) } just Runs

            When("주문 10 결제 요청을 보내면") {
                val response = postJson("/order/place", """{"orderId":10}""")

                Then("200이고 서비스를 한 번 부른다") {
                    response.status shouldBe HttpStatus.OK.value()
                    verify(exactly = 1) { sagaOrchestrator.placeOrder(PlaceOrderCommand(10L)) }
                }
            }
        }
    }
}
