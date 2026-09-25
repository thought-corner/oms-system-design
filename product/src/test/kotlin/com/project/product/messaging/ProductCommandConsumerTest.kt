package com.project.product.messaging

import com.google.protobuf.InvalidProtocolBufferException
import com.project.common.exception.BusinessException
import com.project.message.product.StockBuyCommand
import com.project.message.product.StockCancelCommand
import com.project.product.exception.ProductErrorCode
import com.project.product.service.DeadLetterAlertService
import com.project.product.service.ProductService
import com.project.product.service.dto.BuyCancelCommand
import com.project.product.service.dto.BuyCommand
import com.project.product.service.dto.DeadLetterCommand
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.Called
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.common.header.internals.RecordHeader
import org.springframework.dao.CannotAcquireLockException
import org.springframework.kafka.support.KafkaHeaders

private val BUY_BYTES: ByteArray = StockBuyCommand.newBuilder()
    .setSagaId("saga-1")
    .setOrderId(10L)
    .addItems(StockBuyCommand.Item.newBuilder().setProductId(1L).setQuantity(2L))
    .addItems(StockBuyCommand.Item.newBuilder().setProductId(2L).setQuantity(1L))
    .build()
    .toByteArray()

private val CANCEL_BYTES: ByteArray = StockCancelCommand.newBuilder().setSagaId("saga-1").setOrderId(10L).build().toByteArray()

private val BROKEN_BYTES: ByteArray = byteArrayOf(0x0A, 0x05, 0x73)

private val BUY_COMMAND = BuyCommand("saga-1", 10L, listOf(BuyCommand.Item(1L, 2L), BuyCommand.Item(2L, 1L)))

private fun record(messageType: String?, value: ByteArray, topic: String = "cmd.product", vararg extraHeaders: Pair<String, String>) =
    ConsumerRecord(topic, 0, 0L, "10", value).also { record ->
        record.headers().add(RecordHeader("sagaId", "saga-1".toByteArray()))
        messageType?.let { record.headers().add(RecordHeader("messageType", it.toByteArray())) }
        extraHeaders.forEach { (name, headerValue) -> record.headers().add(RecordHeader(name, headerValue.toByteArray())) }
    }

private fun consumer(productService: ProductService, deadLetterService: DeadLetterAlertService = mockk(relaxed = true)) =
    ProductCommandConsumer(productService, deadLetterService)

class ProductCommandConsumerTest : BehaviorSpec({

    Given("messageType 헤더가 없는 레코드") {
        val productService = mockk<ProductService>()

        When("소비하면") {
            val exception = shouldThrow<IllegalArgumentException> { consumer(productService).onCommand(record(null, BUY_BYTES)) }

            Then("재시도 없이 DLT 로 갈 IllegalArgumentException 이고 서비스를 부르지 않는다") {
                exception.message shouldBe "unknown messageType=null"
                verify { productService wasNot Called }
            }
        }
    }

    Given("모르는 messageType 의 레코드") {
        val productService = mockk<ProductService>()

        When("소비하면") {
            val exception = shouldThrow<IllegalArgumentException> { consumer(productService).onCommand(record("POINT_USE", BUY_BYTES)) }

            Then("IllegalArgumentException 이고 서비스를 부르지 않는다") {
                exception.message shouldBe "unknown messageType=POINT_USE"
                verify { productService wasNot Called }
            }
        }
    }

    Given("Protobuf 로 읽을 수 없는 STOCK_BUY 레코드") {
        val productService = mockk<ProductService>()

        When("소비하면") {
            shouldThrow<InvalidProtocolBufferException> { consumer(productService).onCommand(record("STOCK_BUY", BROKEN_BYTES)) }

            Then("재시도 없이 DLT 로 갈 InvalidProtocolBufferException 이고 서비스를 부르지 않는다") {
                verify { productService wasNot Called }
            }
        }
    }

    Given("sagaId 가 빠진 STOCK_CANCEL 레코드") {
        val productService = mockk<ProductService>()
        val value = StockCancelCommand.newBuilder().setOrderId(10L).build().toByteArray()

        When("소비하면") {
            val exception = shouldThrow<IllegalArgumentException> { consumer(productService).onCommand(record("STOCK_CANCEL", value)) }

            Then("재시도 없이 DLT 로 갈 IllegalArgumentException 이고 서비스를 부르지 않는다") {
                exception.message shouldBe "sagaId missing"
                verify { productService wasNot Called }
            }
        }
    }

    Given("orderId 가 빠진 STOCK_BUY 레코드") {
        val productService = mockk<ProductService>()
        val value = StockBuyCommand.newBuilder().setSagaId("saga-1").build().toByteArray()

        When("소비하면") {
            val exception = shouldThrow<IllegalArgumentException> { consumer(productService).onCommand(record("STOCK_BUY", value)) }

            Then("재시도 없이 DLT 로 갈 IllegalArgumentException 이고 서비스를 부르지 않는다") {
                exception.message shouldBe "orderId missing: sagaId=saga-1"
                verify { productService wasNot Called }
            }
        }
    }

    Given("항목이 없거나 항목의 productId·quantity 가 빠진 STOCK_BUY 레코드") {
        val productService = mockk<ProductService>()
        val base = StockBuyCommand.newBuilder().setSagaId("saga-1").setOrderId(10L)
        val values = listOf(
            base.clone().build(),
            base.clone().addItems(StockBuyCommand.Item.newBuilder().setQuantity(1L)).build(),
            base.clone().addItems(StockBuyCommand.Item.newBuilder().setProductId(1L)).build(),
        ).map { it.toByteArray() }

        When("소비하면") {
            val messages = values.map { value ->
                shouldThrow<IllegalArgumentException> { consumer(productService).onCommand(record("STOCK_BUY", value)) }.message
            }

            Then("재고를 건드리지 않고 빠진 필드를 밝히는 IllegalArgumentException 이다") {
                messages shouldBe listOf(
                    "items missing: sagaId=saga-1",
                    "item missing productId or quantity: sagaId=saga-1",
                    "item missing productId or quantity: sagaId=saga-1",
                )
                verify { productService wasNot Called }
            }
        }
    }

    Given("재고가 부족한 STOCK_BUY 레코드") {
        val productService = mockk<ProductService>()
        every { productService.buy(BUY_COMMAND) } throws BusinessException(ProductErrorCode.INSUFFICIENT_STOCK)
        every { productService.replyBuyFailed(any(), any()) } returns Unit

        When("소비하면") {
            consumer(productService).onCommand(record("STOCK_BUY", BUY_BYTES))

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
            consumer(productService).onCommand(record("STOCK_BUY", BUY_BYTES))

            Then("SAGA_ALREADY_COMPENSATED 실패 응답을 기록한다") {
                verify(exactly = 1) { productService.replyBuyFailed(BUY_COMMAND, ProductErrorCode.SAGA_ALREADY_COMPENSATED) }
            }
        }
    }

    Given("락 대기가 끝나지 않는 STOCK_BUY 레코드") {
        val productService = mockk<ProductService>()
        every { productService.buy(BUY_COMMAND) } throws CannotAcquireLockException("lock wait timeout")

        When("소비하면") {
            shouldThrow<CannotAcquireLockException> { consumer(productService).onCommand(record("STOCK_BUY", BUY_BYTES)) }

            Then("실패 응답을 남기지 않고 예외를 재시도에 넘긴다") {
                verify(exactly = 0) { productService.replyBuyFailed(any(), any()) }
            }
        }
    }

    Given("수량 합계가 넘치는 STOCK_BUY 레코드") {
        val productService = mockk<ProductService>()
        every { productService.buy(BUY_COMMAND) } throws ArithmeticException("long overflow")

        When("소비하면") {
            shouldThrow<ArithmeticException> { consumer(productService).onCommand(record("STOCK_BUY", BUY_BYTES)) }

            Then("불변식 위반이라 실패 응답 없이 DLT 로 갈 예외를 그대로 던진다") {
                verify(exactly = 0) { productService.replyBuyFailed(any(), any()) }
            }
        }
    }

    Given("정상 STOCK_BUY 레코드") {
        val productService = mockk<ProductService>()
        every { productService.buy(BUY_COMMAND) } just Runs

        When("소비하면") {
            consumer(productService).onCommand(record("STOCK_BUY", BUY_BYTES))

            Then("같은 메시지를 서비스 커맨드로 옮겨 차감을 부르고 실패 응답은 없다") {
                verify(exactly = 1) { productService.buy(BUY_COMMAND) }
                verify(exactly = 0) { productService.replyBuyFailed(any(), any()) }
            }
        }
    }

    Given("STOCK_CANCEL 레코드") {
        val productService = mockk<ProductService>()
        every { productService.cancel(BuyCancelCommand("saga-1", 10L)) } just Runs

        When("소비하면") {
            consumer(productService).onCommand(record("STOCK_CANCEL", CANCEL_BYTES))

            Then("보상을 부른다") {
                verify(exactly = 1) { productService.cancel(BuyCancelCommand("saga-1", 10L)) }
            }
        }
    }

    Given("되돌릴 상품이 사라져 보상이 BusinessException 을 낸 STOCK_CANCEL 레코드") {
        val productService = mockk<ProductService>()
        every { productService.cancel(any()) } throws BusinessException(ProductErrorCode.PRODUCT_NOT_FOUND)

        When("소비하면") {
            shouldThrow<BusinessException> { consumer(productService).onCommand(record("STOCK_CANCEL", CANCEL_BYTES)) }

            Then("보상은 FAILED 로 응답하지 않으므로 실패 응답 없이 예외를 재시도에 넘긴다") {
                verify(exactly = 0) { productService.replyBuyFailed(any(), any()) }
            }
        }
    }

    Given("재시도를 모두 소진해 DLT 에 도착한 레코드") {
        val productService = mockk<ProductService>()
        val deadLetterService = mockk<DeadLetterAlertService>(relaxed = true)
        val deadLetter = record(
            "STOCK_BUY",
            BUY_BYTES,
            "cmd.product-dlt",
            KafkaHeaders.ORIGINAL_TOPIC to "cmd.product",
            KafkaHeaders.EXCEPTION_FQCN to "org.springframework.kafka.listener.ListenerExecutionFailedException",
            KafkaHeaders.EXCEPTION_CAUSE_FQCN to "java.lang.IllegalStateException",
            KafkaHeaders.EXCEPTION_MESSAGE to "db down",
        )

        When("DLT 핸들러가 받으면") {
            consumer(productService, deadLetterService).onDeadLetter(deadLetter)

            Then("원래 토픽·키·헤더·원인 예외를 실어 운영자에게 알리고 서비스 로직은 다시 부르지 않는다") {
                verify(exactly = 1) {
                    deadLetterService.handle(
                        DeadLetterCommand("cmd.product", "10", "saga-1", "STOCK_BUY", "java.lang.IllegalStateException", "db down"),
                    )
                }
                verify { productService wasNot Called }
            }
        }
    }

    Given("원래 토픽·원인 예외 헤더가 없는 DLT 레코드") {
        val deadLetterService = mockk<DeadLetterAlertService>(relaxed = true)
        val deadLetter = record(
            "STOCK_BUY",
            BUY_BYTES,
            "cmd.product-dlt",
            KafkaHeaders.EXCEPTION_FQCN to "java.lang.IllegalArgumentException",
        )

        When("DLT 핸들러가 받으면") {
            consumer(mockk(), deadLetterService).onDeadLetter(deadLetter)

            Then("레코드 토픽과 최상위 예외 이름으로 알린다") {
                verify(exactly = 1) {
                    deadLetterService.handle(
                        DeadLetterCommand("cmd.product-dlt", "10", "saga-1", "STOCK_BUY", "java.lang.IllegalArgumentException", null),
                    )
                }
            }
        }
    }
})
