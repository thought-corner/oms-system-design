package com.project.operation.service.worker

import com.project.operation.client.AlertSender
import com.project.operation.client.KafkaMessagePublisher
import com.project.operation.client.OutboxPublishFailedAlert
import com.project.operation.client.dto.OutgoingMessage
import com.project.operation.client.dto.PublishOutcome
import com.project.operation.client.dto.UnpublishedKind
import com.project.operation.domain.OutboxFailure
import com.project.operation.domain.OutboxSource
import com.project.operation.domain.PublishFailure
import com.project.operation.fixture.OutboxFixture.message
import com.project.operation.service.MessageHeaders
import com.project.operation.service.OutboxService
import com.project.operation.service.policy.OutboxRelayPolicy
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.springframework.dao.DataAccessResourceFailureException

private class RelayFixture {
    val outboxService: OutboxService = mockk(relaxed = true)
    val publisher: KafkaMessagePublisher = mockk()
    val alertSender: AlertSender = mockk(relaxed = true)
    val relay = OutboxRelay(outboxService, publisher, alertSender)

    init {
        every { outboxService.claim(any()) } returns emptyList()
    }
}

class OutboxRelayTest : BehaviorSpec({

    Given("어느 스키마에도 미발행 행이 없는 상태") {
        val f = RelayFixture()

        When("릴레이가 깨어나면") {
            f.relay.relay()

            Then("네 스키마를 모두 보고 아무것도 보내지 않는다") {
                OutboxSource.entries.forEach { verify(exactly = 1) { f.outboxService.claim(it) } }
                verify(exactly = 0) { f.publisher.publishAll(any(), any()) }
                verify(exactly = 0) { f.outboxService.markPublished(any(), any()) }
            }
        }
    }

    Given("order 스키마는 권한 오류가 나고 payment 스키마에 세 행이 있는데 그중 한 행만 브로커 확인을 못 받는 경우") {
        val f = RelayFixture()
        every { f.outboxService.claim(OutboxSource.ORDER) } throws DataAccessResourceFailureException("SELECT command denied")
        val unknownTopic = message(2, "no.such.topic")
        every { f.outboxService.claim(OutboxSource.PAYMENT) } returns listOf(message(1), unknownTopic, message(3))
        val sent = slot<List<OutgoingMessage>>()
        every { f.publisher.publishAll(capture(sent), OutboxRelayPolicy.PUBLISH_DEADLINE) } returns listOf(
            PublishOutcome.Acked,
            PublishOutcome.Unpublished(UnpublishedKind.RETRIABLE_FAILURE, "UnknownTopicOrPartitionException"),
            PublishOutcome.Acked,
        )

        When("릴레이가 두 번 깨어나면") {
            f.relay.relay()
            f.relay.relay()

            Then("다른 스키마는 계속 처리하고, 확인받은 행만 PUBLISHED 로 표시한다") {
                verify(exactly = 2) { f.outboxService.claim(OutboxSource.PAYMENT) }
                verify(exactly = 2) { f.outboxService.markPublished(OutboxSource.PAYMENT, listOf(1L, 3L)) }
                verify(exactly = 0) { f.outboxService.markPublished(OutboxSource.ORDER, any()) }
            }

            Then("같은 배치의 다른 행은 확인받았으므로 재시도 가능한 실패라도 그 행의 실패로 센다") {
                verify(exactly = 2) {
                    f.outboxService.recordFailures(
                        OutboxSource.PAYMENT,
                        listOf(PublishFailure(unknownTopic, "UnknownTopicOrPartitionException")),
                    )
                }
            }

            Then("레코드는 키 orderId, 값 payload, 헤더 sagaId·messageType 으로 나간다") {
                val first = sent.captured.first()
                first.topic shouldBe "product.command"
                first.key shouldBe "10"
                first.payload shouldBe byteArrayOf(0x08, 0x01)
                first.headers shouldBe mapOf(
                    MessageHeaders.MESSAGE_ID to "message-1",
                    MessageHeaders.SAGA_ID to "saga-1",
                    MessageHeaders.MESSAGE_TYPE to "STOCK_BUY",
                )
            }
        }

        When("order 스키마가 회복되면") {
            every { f.outboxService.claim(OutboxSource.ORDER) } returns emptyList()
            f.relay.relay(OutboxSource.ORDER)

            Then("예외 없이 계속 폴링한다") {
                verify(atLeast = 1) { f.outboxService.claim(OutboxSource.ORDER) }
            }
        }
    }

    Given("브로커가 닿지 않아 모든 행이 재시도 가능한 실패이거나 보내지 못한 배치") {
        val f = RelayFixture()
        every { f.outboxService.claim(OutboxSource.PRODUCT) } returns listOf(message(1), message(2), message(3, "point.command"))
        every { f.publisher.publishAll(any(), any()) } returns listOf(
            PublishOutcome.Unpublished(UnpublishedKind.RETRIABLE_FAILURE, "TimeoutException: Expiring 1 record(s)"),
            PublishOutcome.Unpublished(UnpublishedKind.RETRIABLE_FAILURE, "broker ack timed out"),
            PublishOutcome.Unpublished(UnpublishedKind.NOT_SENT, "deadline exceeded before send"),
        )

        When("릴레이가 여러 임대 주기 동안 계속 깨어나면") {
            repeat(OutboxRelayPolicy.MAX_PUBLISH_ATTEMPTS + 1) { f.relay.relay(OutboxSource.PRODUCT) }

            Then("실패를 전혀 세지 않아 행은 fail_count 그대로 PENDING 으로 남고 알림도 없다") {
                verify(exactly = 0) { f.outboxService.recordFailures(any(), any()) }
                verify(exactly = 0) { f.outboxService.markPublished(any(), any()) }
                verify(exactly = 0) { f.alertSender.send(any<OutboxPublishFailedAlert>()) }
            }
        }

        When("브로커가 돌아와 확인을 받으면") {
            every { f.publisher.publishAll(any(), any()) } returns List(3) { PublishOutcome.Acked }
            f.relay.relay(OutboxSource.PRODUCT)

            Then("PUBLISHED 로 표시한다") {
                verify(exactly = 1) { f.outboxService.markPublished(OutboxSource.PRODUCT, listOf(1L, 2L, 3L)) }
                verify(exactly = 0) { f.outboxService.recordFailures(any(), any()) }
            }
        }
    }

    Given("확인받은 행이 없는 배치에 재시도 가능한 실패와 영구 실패가 섞인 경우") {
        val f = RelayFixture()
        val tooLarge = message(2, "point.command")
        every { f.outboxService.claim(OutboxSource.POINT) } returns listOf(message(1), tooLarge)
        every { f.publisher.publishAll(any(), any()) } returns listOf(
            PublishOutcome.Unpublished(UnpublishedKind.RETRIABLE_FAILURE, "TimeoutException: Expiring 1 record(s)"),
            PublishOutcome.Unpublished(UnpublishedKind.PERMANENT_FAILURE, "RecordTooLargeException: too large"),
        )

        When("릴레이가 깨어나면") {
            f.relay.relay(OutboxSource.POINT)

            Then("영구 실패만 세고 재시도 가능한 실패는 세지 않는다") {
                verify(exactly = 1) {
                    f.outboxService.recordFailures(OutboxSource.POINT, listOf(PublishFailure(tooLarge, "RecordTooLargeException: too large")))
                }
            }
        }
    }

    Given("깨진 토픽의 첫 행은 재시도 가능한 실패, 같은 토픽의 둘째 행은 보내지 못하고, 다른 행은 확인받은 배치") {
        val f = RelayFixture()
        val attempted = message(1, "no.such.topic")
        every { f.outboxService.claim(OutboxSource.POINT) } returns listOf(attempted, message(2, "no.such.topic"), message(3))
        every { f.publisher.publishAll(any(), any()) } returns listOf(
            PublishOutcome.Unpublished(UnpublishedKind.RETRIABLE_FAILURE, "TimeoutException: not present in metadata"),
            PublishOutcome.Unpublished(UnpublishedKind.NOT_SENT, "TimeoutException: not present in metadata"),
            PublishOutcome.Acked,
        )
        every { f.outboxService.recordFailures(OutboxSource.POINT, any()) } returns emptyList()

        When("릴레이가 깨어나면") {
            f.relay.relay(OutboxSource.POINT)

            Then("실제로 보낸 행만 실패로 세고 보내지 못한 행은 임대 만료 뒤 다시 시도한다") {
                verify(exactly = 1) {
                    f.outboxService.recordFailures(
                        OutboxSource.POINT,
                        listOf(PublishFailure(attempted, "TimeoutException: not present in metadata")),
                    )
                }
                verify(exactly = 1) { f.outboxService.markPublished(OutboxSource.POINT, listOf(3L)) }
            }

            Then("아직 한도 전이라 알리지 않는다") {
                verify(exactly = 0) { f.alertSender.send(any<OutboxPublishFailedAlert>()) }
            }
        }
    }

    Given("시도하지 못하고 모두 건너뛴 배치") {
        val f = RelayFixture()
        every { f.outboxService.claim(OutboxSource.ORDER) } returns listOf(message(1))
        every { f.publisher.publishAll(any(), any()) } returns listOf(
            PublishOutcome.Unpublished(UnpublishedKind.NOT_SENT, "deadline exceeded before send"),
        )

        When("릴레이가 깨어나면") {
            f.relay.relay(OutboxSource.ORDER)

            Then("실패로 세지 않는다") {
                verify(exactly = 0) { f.outboxService.recordFailures(any(), any()) }
            }
        }
    }

    Given("네 번 실패한 행이 다섯 번째로 영구 실패하는 경우") {
        val f = RelayFixture()
        val broken = message(9, "no.such.topic")
        every { f.outboxService.claim(OutboxSource.PAYMENT) } returnsMany listOf(listOf(broken), emptyList())
        every { f.publisher.publishAll(any(), any()) } returns listOf(PublishOutcome.Unpublished(UnpublishedKind.PERMANENT_FAILURE, "InvalidTopicException"))
        every { f.outboxService.recordFailures(OutboxSource.PAYMENT, any()) } returns listOf(
            OutboxFailure(broken, OutboxRelayPolicy.MAX_PUBLISH_ATTEMPTS, "InvalidTopicException"),
        )

        When("릴레이가 두 번 깨어나면") {
            f.relay.relay(OutboxSource.PAYMENT)
            f.relay.relay(OutboxSource.PAYMENT)

            Then("FAILED 로 바뀐 때 한 번만 운영자에게 알린다") {
                verify(exactly = 1) {
                    f.alertSender.send(
                        OutboxPublishFailedAlert(
                            schema = "payment",
                            outboxId = 9,
                            messageId = "message-9",
                            topic = "no.such.topic",
                            sagaId = "saga-9",
                            messageType = "STOCK_BUY",
                            failCount = 5,
                            lastError = "InvalidTopicException",
                        ),
                    )
                }
                verify(exactly = 1) { f.alertSender.send(any<OutboxPublishFailedAlert>()) }
            }
        }
    }
})
