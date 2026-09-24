package com.project.point.messaging

import com.project.point.service.CommandDeadLetterService
import com.project.point.service.dto.DeadLetterCommand
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.springframework.kafka.support.KafkaHeaders

class PointCommandDeadLetterHandlerTest : BehaviorSpec({

    Given("재시도 토픽을 모두 거쳐 DLT 에 온 레코드") {
        val service = mockk<CommandDeadLetterService>()
        val reported = slot<DeadLetterCommand>()
        every { service.handle(capture(reported)) } just Runs
        val record = ConsumerRecord("cmd.point-dlt", 1, 5L, "10", "{}").also {
            it.headers().add(MessageHeaders.SAGA_ID, "saga-1".toByteArray())
            it.headers().add(MessageHeaders.MESSAGE_TYPE, "POINT_USE".toByteArray())
            it.headers().add(KafkaHeaders.EXCEPTION_FQCN, "org.springframework.kafka.listener.ListenerExecutionFailedException".toByteArray())
            it.headers().add(KafkaHeaders.EXCEPTION_CAUSE_FQCN, "java.lang.IllegalStateException".toByteArray())
            it.headers().add(KafkaHeaders.EXCEPTION_MESSAGE, "db down".toByteArray())
        }

        When("DLT 핸들러가 받으면") {
            PointCommandDeadLetterHandler(service).handle(record)

            Then("키와 헤더에서 orderId·sagaId·messageType·원인 예외를 꺼내 보고한다") {
                reported.captured shouldBe DeadLetterCommand(
                    topic = "cmd.point-dlt",
                    orderId = "10",
                    sagaId = "saga-1",
                    messageType = "POINT_USE",
                    exceptionClass = "java.lang.IllegalStateException",
                    exceptionMessage = "db down",
                )
            }
        }
    }

    Given("원인 예외 헤더가 없는 DLT 레코드") {
        val service = mockk<CommandDeadLetterService>()
        val reported = slot<DeadLetterCommand>()
        every { service.handle(capture(reported)) } just Runs
        val record = ConsumerRecord("cmd.point-dlt", 0, 0L, "10", "{").also {
            it.headers().add(KafkaHeaders.EXCEPTION_FQCN, "tools.jackson.core.exc.StreamReadException".toByteArray())
        }

        When("DLT 핸들러가 받으면") {
            PointCommandDeadLetterHandler(service).handle(record)

            Then("예외 클래스 헤더로 대신하고 없는 헤더는 null 로 보고한다") {
                reported.captured.exceptionClass shouldBe "tools.jackson.core.exc.StreamReadException"
                reported.captured.sagaId shouldBe null
                reported.captured.messageType shouldBe null
            }
        }
    }
})
