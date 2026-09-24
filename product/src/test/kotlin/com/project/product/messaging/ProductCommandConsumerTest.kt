package com.project.product.messaging

import com.project.common.exception.BusinessException
import com.project.product.exception.ProductErrorCode
import com.project.product.service.CommandDeadLetterService
import com.project.product.service.ProductService
import com.project.product.service.dto.BuyCancelCommand
import com.project.product.service.dto.BuyCommand
import com.project.product.service.dto.DeadLetterCommand
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.common.header.internals.RecordHeader
import org.springframework.dao.CannotAcquireLockException
import org.springframework.kafka.support.KafkaHeaders
import tools.jackson.core.JacksonException
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule

private val OBJECT_MAPPER = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()

private const val BUY_JSON = """{"sagaId":"saga-1","orderId":10,"items":[{"productId":1,"quantity":2},{"productId":2,"quantity":1}]}"""

private const val CANCEL_JSON = """{"sagaId":"saga-1","orderId":10}"""

private val BUY_COMMAND = BuyCommand("saga-1", 10L, listOf(BuyCommand.Item(1L, 2L), BuyCommand.Item(2L, 1L)))

private fun record(messageType: String?, value: String, topic: String = "cmd.product", vararg extraHeaders: Pair<String, String>) =
    ConsumerRecord(topic, 0, 0L, "10", value).also { record ->
        record.headers().add(RecordHeader("sagaId", "saga-1".toByteArray()))
        messageType?.let { record.headers().add(RecordHeader("messageType", it.toByteArray())) }
        extraHeaders.forEach { (name, headerValue) -> record.headers().add(RecordHeader(name, headerValue.toByteArray())) }
    }

private fun consumer(productService: ProductService, deadLetterService: CommandDeadLetterService = mockk(relaxed = true)) =
    ProductCommandConsumer(productService, deadLetterService, OBJECT_MAPPER)

class ProductCommandConsumerTest : BehaviorSpec({

    Given("messageType 헤더가 없는 레코드") {
        val productService = mockk<ProductService>()

        When("소비하면") {
            val exception = shouldThrow<IllegalArgumentException> { consumer(productService).consume(record(null, BUY_JSON)) }

            Then("재시도 없이 DLT 로 갈 IllegalArgumentException 이고 서비스를 부르지 않는다") {
                exception.message shouldBe "messageType=null"
                verify { productService wasNot Called }
            }
        }
    }

    Given("모르는 messageType 의 레코드") {
        val productService = mockk<ProductService>()

        When("소비하면") {
            val exception = shouldThrow<IllegalArgumentException> { consumer(productService).consume(record("POINT_USE", BUY_JSON)) }

            Then("IllegalArgumentException 이고 서비스를 부르지 않는다") {
                exception.message shouldBe "messageType=POINT_USE"
                verify { productService wasNot Called }
            }
        }
    }

    Given("JSON 으로 읽을 수 없는 STOCK_BUY 레코드") {
        val productService = mockk<ProductService>()

        When("소비하면") {
            shouldThrow<JacksonException> { consumer(productService).consume(record("STOCK_BUY", "{not json")) }

            Then("재시도 없이 DLT 로 갈 JacksonException 이고 서비스를 부르지 않는다") {
                verify { productService wasNot Called }
            }
        }
    }

    Given("sagaId 가 빠진 STOCK_CANCEL 레코드") {
        val productService = mockk<ProductService>()

        When("소비하면") {
            shouldThrow<JacksonException> { consumer(productService).consume(record("STOCK_CANCEL", """{"orderId":10}""")) }

            Then("JacksonException 이고 서비스를 부르지 않는다") {
                verify { productService wasNot Called }
            }
        }
    }

    Given("재고가 부족한 STOCK_BUY 레코드") {
        val productService = mockk<ProductService>()
        every { productService.buy(BUY_COMMAND) } throws BusinessException(ProductErrorCode.INSUFFICIENT_STOCK)
        every { productService.replyBuyFailed(any(), any()) } returns Unit

        When("소비하면") {
            consumer(productService).consume(record("STOCK_BUY", BUY_JSON))

            Then("예외를 삼키고 차감 트랜잭션과 별개로 실패 응답을 기록한다") {
                verify(exactly = 1) { productService.buy(BUY_COMMAND) }
                verify(exactly = 1) { productService.replyBuyFailed(BUY_COMMAND, ProductErrorCode.INSUFFICIENT_STOCK) }
            }
        }
    }

    Given("보상이 먼저 끝난 사가의 늦은 STOCK_BUY 레코드") {
        val productService = mockk<ProductService>()
        every { productService.buy(BUY_COMMAND) } throws BusinessException(ProductErrorCode.SAGA_ALREADY_COMPENSATED)
        every { productService.replyBuyFailed(any(), any()) } returns Unit

        When("소비하면") {
            consumer(productService).consume(record("STOCK_BUY", BUY_JSON))

            Then("SAGA_ALREADY_COMPENSATED 실패 응답을 기록한다") {
                verify(exactly = 1) { productService.replyBuyFailed(BUY_COMMAND, ProductErrorCode.SAGA_ALREADY_COMPENSATED) }
            }
        }
    }

    Given("락 대기가 끝나지 않는 STOCK_BUY 레코드") {
        val productService = mockk<ProductService>()
        every { productService.buy(BUY_COMMAND) } throws CannotAcquireLockException("lock wait timeout")

        When("소비하면") {
            shouldThrow<CannotAcquireLockException> { consumer(productService).consume(record("STOCK_BUY", BUY_JSON)) }

            Then("실패 응답을 남기지 않고 예외를 재시도에 넘긴다") {
                verify(exactly = 0) { productService.replyBuyFailed(any(), any()) }
            }
        }
    }

    Given("수량 합계가 넘치는 STOCK_BUY 레코드") {
        val productService = mockk<ProductService>()
        every { productService.buy(BUY_COMMAND) } throws ArithmeticException("long overflow")

        When("소비하면") {
            shouldThrow<ArithmeticException> { consumer(productService).consume(record("STOCK_BUY", BUY_JSON)) }

            Then("불변식 위반이라 실패 응답 없이 DLT 로 갈 예외를 그대로 던진다") {
                verify(exactly = 0) { productService.replyBuyFailed(any(), any()) }
            }
        }
    }

    Given("정상 STOCK_BUY 레코드") {
        val productService = mockk<ProductService>()
        every { productService.buy(BUY_COMMAND) } returns 400L

        When("소비하면") {
            consumer(productService).consume(record("STOCK_BUY", BUY_JSON))

            Then("같은 JSON 을 서비스 커맨드로 옮겨 차감을 부르고 실패 응답은 없다") {
                verify(exactly = 1) { productService.buy(BUY_COMMAND) }
                verify(exactly = 0) { productService.replyBuyFailed(any(), any()) }
            }
        }
    }

    Given("STOCK_CANCEL 레코드") {
        val productService = mockk<ProductService>()
        every { productService.cancel(BuyCancelCommand("saga-1", 10L)) } returns 0L

        When("소비하면") {
            consumer(productService).consume(record("STOCK_CANCEL", CANCEL_JSON))

            Then("보상을 부른다") {
                verify(exactly = 1) { productService.cancel(BuyCancelCommand("saga-1", 10L)) }
            }
        }
    }

    Given("되돌릴 상품이 사라져 보상이 BusinessException 을 낸 STOCK_CANCEL 레코드") {
        val productService = mockk<ProductService>()
        every { productService.cancel(any()) } throws BusinessException(ProductErrorCode.PRODUCT_NOT_FOUND)

        When("소비하면") {
            shouldThrow<BusinessException> { consumer(productService).consume(record("STOCK_CANCEL", CANCEL_JSON)) }

            Then("보상은 FAILED 로 응답하지 않으므로 실패 응답 없이 예외를 재시도에 넘긴다") {
                verify(exactly = 0) { productService.replyBuyFailed(any(), any()) }
            }
        }
    }

    Given("재시도를 모두 소진해 DLT 에 도착한 레코드") {
        val productService = mockk<ProductService>()
        val deadLetterService = mockk<CommandDeadLetterService>(relaxed = true)
        val deadLetter = record(
            "STOCK_BUY",
            BUY_JSON,
            "cmd.product-dlt",
            KafkaHeaders.EXCEPTION_FQCN to "org.springframework.kafka.listener.ListenerExecutionFailedException",
            KafkaHeaders.EXCEPTION_CAUSE_FQCN to "java.lang.IllegalStateException",
            KafkaHeaders.EXCEPTION_MESSAGE to "db down",
        )

        When("DLT 핸들러가 받으면") {
            consumer(productService, deadLetterService).onDeadLetter(deadLetter)

            Then("토픽·키·헤더·원인 예외를 실어 운영자에게 알리고 서비스 로직은 다시 부르지 않는다") {
                verify(exactly = 1) {
                    deadLetterService.handle(
                        DeadLetterCommand("cmd.product-dlt", "10", "saga-1", "STOCK_BUY", "java.lang.IllegalStateException", "db down"),
                    )
                }
                verify { productService wasNot Called }
            }
        }
    }

    Given("원인 예외 헤더가 없는 DLT 레코드") {
        val deadLetterService = mockk<CommandDeadLetterService>(relaxed = true)
        val deadLetter = record(
            "STOCK_BUY",
            BUY_JSON,
            "cmd.product-dlt",
            KafkaHeaders.EXCEPTION_FQCN to "java.lang.IllegalArgumentException",
        )

        When("DLT 핸들러가 받으면") {
            consumer(mockk(), deadLetterService).onDeadLetter(deadLetter)

            Then("최상위 예외 이름으로 알린다") {
                verify(exactly = 1) {
                    deadLetterService.handle(
                        DeadLetterCommand("cmd.product-dlt", "10", "saga-1", "STOCK_BUY", "java.lang.IllegalArgumentException", null),
                    )
                }
            }
        }
    }
})
