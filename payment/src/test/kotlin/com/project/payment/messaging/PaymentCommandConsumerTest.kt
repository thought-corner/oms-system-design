package com.project.payment.messaging

import com.google.protobuf.InvalidProtocolBufferException
import com.project.common.exception.BusinessException
import com.project.message.payment.PaymentCancelCommand
import com.project.message.payment.PaymentPayCommand
import com.project.payment.exception.PaymentErrorCode
import com.project.payment.fixture.PaymentFixture
import com.project.payment.service.DeadLetterAlertService
import com.project.payment.service.PaymentService
import com.project.payment.service.dto.DeadLetter
import com.project.payment.service.dto.PayCancelCommand
import com.project.payment.service.dto.PayCommand
import com.project.payment.service.dto.PayResult
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.Called
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.common.header.internals.RecordHeader
import org.springframework.dao.QueryTimeoutException

private val PAY_BYTES: ByteArray =
    PaymentPayCommand.newBuilder().setSagaId("saga-1").setOrderId(1L).setUserId(1L).setAmount(400L).build().toByteArray()
private val CANCEL_BYTES: ByteArray =
    PaymentCancelCommand.newBuilder().setSagaId("saga-1").setOrderId(1L).build().toByteArray()
private val BROKEN_BYTES: ByteArray = byteArrayOf(0x0A, 0x7F, 0x01)
private val PAY_COMMAND = PayCommand(sagaId = "saga-1", orderId = 1L, userId = 1L, amount = 400L)

private fun record(
    messageType: String?,
    value: ByteArray,
    topic: String = "cmd.payment",
    headers: Map<String, String> = emptyMap(),
): ConsumerRecord<String, ByteArray> =
    ConsumerRecord(topic, 0, 0L, "1", value).also { record ->
        messageType?.let { record.headers().add(RecordHeader("messageType", it.toByteArray())) }
        record.headers().add(RecordHeader("sagaId", "saga-1".toByteArray()))
        headers.forEach { (name, headerValue) -> record.headers().add(RecordHeader(name, headerValue.toByteArray())) }
    }

private fun consumer(
    paymentService: PaymentService,
    deadLetterAlertService: DeadLetterAlertService = mockk(),
): PaymentCommandConsumer = PaymentCommandConsumer(paymentService, deadLetterAlertService)

class PaymentCommandConsumerTest : BehaviorSpec({

    Given("잔액·재고와 무관하게 이미 결제된 주문의 PAYMENT_PAY 커맨드") {
        val paymentService = mockk<PaymentService>()
        every { paymentService.pay(PAY_COMMAND) } throws BusinessException(PaymentErrorCode.ALREADY_PAID)
        every { paymentService.recordPayFailure(any(), any()) } just Runs

        When("소비하면") {
            consumer(paymentService).onCommand(record("PAYMENT_PAY", PAY_BYTES))

            Then("예외를 삼키고 별도 트랜잭션 메서드로 FAILED 응답을 남긴다") {
                verify(exactly = 1) { paymentService.recordPayFailure(PAY_COMMAND, PaymentErrorCode.ALREADY_PAID) }
            }
        }
    }

    Given("보상이 먼저 도착한 사가의 늦은 PAYMENT_PAY 커맨드") {
        val paymentService = mockk<PaymentService>()
        every { paymentService.pay(PAY_COMMAND) } throws BusinessException(PaymentErrorCode.SAGA_ALREADY_COMPENSATED)
        every { paymentService.recordPayFailure(any(), any()) } just Runs

        When("소비하면") {
            consumer(paymentService).onCommand(record("PAYMENT_PAY", PAY_BYTES))

            Then("SAGA_ALREADY_COMPENSATED 로 FAILED 응답을 남긴다") {
                verify(exactly = 1) { paymentService.recordPayFailure(PAY_COMMAND, PaymentErrorCode.SAGA_ALREADY_COMPENSATED) }
            }
        }
    }

    Given("DB 가 응답하지 않는 동안의 PAYMENT_PAY 커맨드") {
        val paymentService = mockk<PaymentService>()
        every { paymentService.pay(PAY_COMMAND) } throws QueryTimeoutException("lock wait")

        When("소비하면") {
            val exception = shouldThrow<QueryTimeoutException> { consumer(paymentService).onCommand(record("PAYMENT_PAY", PAY_BYTES)) }

            Then("실패 응답을 남기지 않고 예외를 재시도로 넘긴다") {
                exception.message shouldBe "lock wait"
                verify(exactly = 0) { paymentService.recordPayFailure(any(), any()) }
            }
        }
    }

    Given("모르는 messageType 의 커맨드") {
        val paymentService = mockk<PaymentService>()

        When("소비하면") {
            val exception = shouldThrow<IllegalArgumentException> { consumer(paymentService).onCommand(record("STOCK_BUY", PAY_BYTES)) }

            Then("재시도 없이 DLT 로 갈 IllegalArgumentException 이고 서비스를 부르지 않는다") {
                exception.message shouldContain "STOCK_BUY"
                verify { paymentService wasNot Called }
            }
        }

        When("messageType 헤더가 없으면") {
            shouldThrow<IllegalArgumentException> { consumer(paymentService).onCommand(record(null, PAY_BYTES)) }

            Then("역시 서비스를 부르지 않는다") {
                verify { paymentService wasNot Called }
            }
        }
    }

    Given("Protobuf 바이트가 깨진 커맨드") {
        val paymentService = mockk<PaymentService>()

        When("소비하면") {
            shouldThrow<InvalidProtocolBufferException> { consumer(paymentService).onCommand(record("PAYMENT_PAY", BROKEN_BYTES)) }

            Then("InvalidProtocolBufferException 으로 재시도 없이 DLT 로 가고 서비스를 부르지 않는다") {
                verify { paymentService wasNot Called }
            }
        }
    }

    Given("sagaId·orderId 가 비어 있는 커맨드") {
        val paymentService = mockk<PaymentService>()

        When("sagaId 없는 PAYMENT_PAY 를 소비하면") {
            val exception = shouldThrow<IllegalArgumentException> {
                consumer(paymentService).onCommand(record("PAYMENT_PAY", PaymentPayCommand.newBuilder().setOrderId(1L).build().toByteArray()))
            }

            Then("재시도 없이 DLT 로 갈 IllegalArgumentException 이고 서비스를 부르지 않는다") {
                exception.message shouldContain "sagaId"
                verify { paymentService wasNot Called }
            }
        }

        When("userId 나 amount 가 빠진 PAYMENT_PAY 를 소비하면") {
            val base = PaymentPayCommand.newBuilder().setSagaId("saga-1").setOrderId(1L).setUserId(1L).setAmount(400L)
            val messages = listOf(base.clone().clearUserId().build(), base.clone().clearAmount().build()).map { command ->
                shouldThrow<IllegalArgumentException> { consumer(paymentService).onCommand(record("PAYMENT_PAY", command.toByteArray())) }.message
            }

            Then("0원 결제로 진행하지 않고 빠진 필드를 밝힌다") {
                messages shouldBe listOf("userId missing: sagaId=saga-1", "amount missing: sagaId=saga-1")
                verify { paymentService wasNot Called }
            }
        }

        When("orderId 없는 PAYMENT_CANCEL 을 소비하면") {
            val exception = shouldThrow<IllegalArgumentException> {
                consumer(paymentService).onCommand(record("PAYMENT_CANCEL", PaymentCancelCommand.newBuilder().setSagaId("saga-1").build().toByteArray()))
            }

            Then("역시 서비스를 부르지 않는다") {
                exception.message shouldContain "orderId"
                verify { paymentService wasNot Called }
            }
        }
    }

    Given("결제된 주문의 PAYMENT_PAY 커맨드") {
        val paymentService = mockk<PaymentService>()
        every { paymentService.pay(PAY_COMMAND) } returns PayResult(1L, PaymentFixture.FIXED_PAID_AT)

        When("소비하면") {
            consumer(paymentService).onCommand(record("PAYMENT_PAY", PAY_BYTES))

            Then("본문을 PayCommand 로 옮겨 결제를 부르고 실패 응답은 남기지 않는다") {
                verify(exactly = 1) { paymentService.pay(PAY_COMMAND) }
                verify(exactly = 0) { paymentService.recordPayFailure(any(), any()) }
            }
        }
    }

    Given("PAYMENT_CANCEL 커맨드") {
        val paymentService = mockk<PaymentService>()
        every { paymentService.cancel(any()) } just Runs

        When("소비하면") {
            consumer(paymentService).onCommand(record("PAYMENT_CANCEL", CANCEL_BYTES))

            Then("본문을 PayCancelCommand 로 옮겨 결제 취소를 부른다") {
                verify(exactly = 1) { paymentService.cancel(PayCancelCommand(sagaId = "saga-1", orderId = 1L)) }
            }
        }
    }

    Given("재시도를 모두 소진해 DLT 에 온 레코드") {
        val deadLetterAlertService = mockk<DeadLetterAlertService>()
        every { deadLetterAlertService.handle(any()) } just Runs
        val dltRecord = record(
            "PAYMENT_PAY",
            PAY_BYTES,
            topic = "cmd.payment-dlt",
            headers = mapOf(
                "kafka_original-topic" to "cmd.payment",
                "kafka_exception-cause-fqcn" to "org.springframework.dao.QueryTimeoutException",
                "kafka_exception-message" to "lock wait",
            ),
        )

        When("DLT 핸들러가 받으면") {
            consumer(mockk(), deadLetterAlertService).onDeadLetter(dltRecord)

            Then("토픽·orderId·sagaId·messageType·예외를 담아 운영자 알림을 보낸다") {
                verify(exactly = 1) {
                    deadLetterAlertService.handle(
                        DeadLetter(
                            topic = "cmd.payment",
                            orderId = "1",
                            sagaId = "saga-1",
                            messageType = "PAYMENT_PAY",
                            exceptionClass = "org.springframework.dao.QueryTimeoutException",
                            exceptionMessage = "lock wait",
                        ),
                    )
                }
            }
        }
    }

    Given("예외 헤더가 원인 없이 실린 DLT 레코드") {
        val deadLetterAlertService = mockk<DeadLetterAlertService>()
        every { deadLetterAlertService.handle(any()) } just Runs
        val dltRecord = record(
            "PAYMENT_PAY",
            BROKEN_BYTES,
            topic = "cmd.payment-dlt",
            headers = mapOf("kafka_exception-fqcn" to "org.springframework.kafka.listener.ListenerExecutionFailedException"),
        )

        When("DLT 핸들러가 받으면") {
            consumer(mockk(), deadLetterAlertService).onDeadLetter(dltRecord)

            Then("DLT 토픽 이름과 바깥 예외 클래스로 알린다") {
                verify(exactly = 1) {
                    deadLetterAlertService.handle(
                        match {
                            it.topic == "cmd.payment-dlt" &&
                                it.exceptionClass == "org.springframework.kafka.listener.ListenerExecutionFailedException" &&
                                it.exceptionMessage == null
                        },
                    )
                }
            }
        }
    }
})
