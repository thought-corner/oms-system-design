package com.project.order.integration

import com.project.order.DbTag
import com.project.order.client.AlertSender
import com.project.order.client.ReplyDeadLetterAlert
import com.project.order.service.OrderPlacementService
import com.project.order.service.OrderService
import com.project.order.service.dto.CreateOrderCommand
import com.project.order.service.dto.PlaceOrderCommand
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.verify
import org.apache.kafka.clients.producer.ProducerRecord
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.kafka.core.KafkaTemplate
import tools.jackson.databind.ObjectMapper
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

@SpringBootTest
@Import(IntegrationTestConfig::class)
class SagaReplyIntegrationTest : BehaviorSpec() {

    @Autowired
    lateinit var kafkaTemplate: KafkaTemplate<String, String>

    @Autowired
    lateinit var orderService: OrderService

    @Autowired
    lateinit var orderPlacementService: OrderPlacementService

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    lateinit var objectMapper: ObjectMapper

    @Autowired
    lateinit var alertSender: AlertSender

    private fun placedOrder(): Pair<Long, String> {
        val orderId = orderService.createOrder(
            CreateOrderCommand(
                userId = 1L,
                orderItems = listOf(CreateOrderCommand.OrderItem(2L, 1L), CreateOrderCommand.OrderItem(1L, 2L)),
            ),
        ).orderId
        orderPlacementService.place(PlaceOrderCommand(orderId, UUID.randomUUID().toString()))
        val sagaId = jdbcTemplate.queryForObject("SELECT saga_id FROM order_saga WHERE order_id = ?", String::class.java, orderId)!!
        return orderId to sagaId
    }

    private fun reply(orderId: Long, sagaId: String, messageType: String, body: String) {
        val record = ProducerRecord<String, String>(IntegrationTestConfig.REPLY_TOPIC, orderId.toString(), body)
        record.headers().add("sagaId", sagaId.toByteArray())
        record.headers().add("messageType", messageType.toByteArray())
        kafkaTemplate.send(record).get()
    }

    private fun replyBody(orderId: Long, sagaId: String, step: String, direction: String, outcome: String, extra: String = ""): String =
        """{"sagaId":"$sagaId","orderId":$orderId,"step":"$step","direction":"$direction","outcome":"$outcome"$extra}"""

    private fun commands(sagaId: String): List<Map<String, Any?>> =
        jdbcTemplate.queryForList(
            "SELECT topic, message_key, message_type, payload, status FROM outbox WHERE saga_id = ? ORDER BY id",
            sagaId,
        )

    private fun sagaRow(sagaId: String): Map<String, Any?> =
        jdbcTemplate.queryForMap("SELECT status, current_step, failure_code, stock_canceled, point_canceled, payment_canceled FROM order_saga WHERE saga_id = ?", sagaId)

    private fun orderStatus(orderId: Long): String? =
        jdbcTemplate.queryForObject("SELECT status FROM orders WHERE id = ?", String::class.java, orderId)

    init {
        extensions(SpringExtension())
        tags(DbTag)

        Given("결제를 시작해 STOCK_BUY 가 outbox 에 들어간 주문") {
            val (orderId, sagaId) = placedOrder()

            Then("진입 트랜잭션이 PLACING · RUNNING · STOCK_BUY(productId 오름차순) 를 함께 남긴다") {
                orderStatus(orderId) shouldBe "PLACING"
                val command = commands(sagaId).single()
                command["topic"] shouldBe "cmd.product"
                command["message_key"] shouldBe orderId.toString()
                command["message_type"] shouldBe "STOCK_BUY"
                command["status"] shouldBe "PENDING"
                val items = objectMapper.readTree(command["payload"] as String)["items"]
                items[0]["productId"].asLong() shouldBe 1L
                items[1]["productId"].asLong() shouldBe 2L
            }

            When("재고 성공 응답이 saga.replies 에 오면") {
                reply(orderId, sagaId, "STOCK_BUY", replyBody(orderId, sagaId, "STOCK", "FORWARD", "SUCCEEDED", ""","result":{"totalPrice":400}"""))

                Then("POINT_USE 400 이 outbox 에 들어간다") {
                    eventually(30.seconds) {
                        commands(sagaId).map { it["message_type"] } shouldContainExactly listOf("STOCK_BUY", "POINT_USE")
                    }
                    val payload = objectMapper.readTree(commands(sagaId).last()["payload"] as String)
                    payload["amount"].asLong() shouldBe 400L
                    payload["userId"].asLong() shouldBe 1L
                }
            }

            When("포인트 성공 응답이 code·result 없이 오면") {
                reply(orderId, sagaId, "POINT_USE", replyBody(orderId, sagaId, "POINT", "FORWARD", "SUCCEEDED", ""","code":null,"result":{}"""))

                Then("PAYMENT_PAY 가 outbox 에 들어간다") {
                    eventually(30.seconds) {
                        commands(sagaId).map { it["message_type"] } shouldContainExactly listOf("STOCK_BUY", "POINT_USE", "PAYMENT_PAY")
                    }
                }
            }

            When("결제 성공 응답이 오면") {
                reply(orderId, sagaId, "PAYMENT_PAY", replyBody(orderId, sagaId, "PAYMENT", "FORWARD", "SUCCEEDED", ""","result":{"paymentId":1,"paidAt":"2026-09-22T12:00:00"}"""))

                Then("AC-2 주문 COMPLETED · 사가 SUCCEEDED 로 닫히고 새 커맨드는 없다") {
                    eventually(30.seconds) {
                        orderStatus(orderId) shouldBe "COMPLETED"
                        sagaRow(sagaId)["status"] shouldBe "SUCCEEDED"
                    }
                    commands(sagaId).size shouldBe 3
                }
            }
        }

        Given("포인트 단계까지 간 주문") {
            val (orderId, sagaId) = placedOrder()
            reply(orderId, sagaId, "STOCK_BUY", replyBody(orderId, sagaId, "STOCK", "FORWARD", "SUCCEEDED", ""","result":{"totalPrice":400}"""))
            eventually(30.seconds) { commands(sagaId).size shouldBe 2 }

            When("잔액 부족 실패 응답이 오면") {
                reply(orderId, sagaId, "POINT_USE", replyBody(orderId, sagaId, "POINT", "FORWARD", "FAILED", ""","code":"INSUFFICIENT_POINT""""))

                Then("AC-4 사가 COMPENSATING · failure_code INSUFFICIENT_POINT 이고 세 보상 커맨드가 들어가며 주문은 PLACING 이다") {
                    eventually(30.seconds) {
                        commands(sagaId).map { it["message_type"] } shouldContainExactly
                            listOf("STOCK_BUY", "POINT_USE", "PAYMENT_CANCEL", "POINT_CANCEL", "STOCK_CANCEL")
                    }
                    sagaRow(sagaId)["status"] shouldBe "COMPENSATING"
                    sagaRow(sagaId)["failure_code"] shouldBe "INSUFFICIENT_POINT"
                    orderStatus(orderId) shouldBe "PLACING"
                    objectMapper.readTree(commands(sagaId).last()["payload"] as String).size() shouldBe 2
                }
            }

            When("세 보상 응답이 오고 그중 하나가 중복되면") {
                reply(orderId, sagaId, "PAYMENT_CANCEL", replyBody(orderId, sagaId, "PAYMENT", "CANCEL", "SUCCEEDED", ""","result":{"canceledAmount":0}"""))
                reply(orderId, sagaId, "PAYMENT_CANCEL", replyBody(orderId, sagaId, "PAYMENT", "CANCEL", "SUCCEEDED", ""","result":{"canceledAmount":0}"""))
                reply(orderId, sagaId, "POINT_CANCEL", replyBody(orderId, sagaId, "POINT", "CANCEL", "SUCCEEDED", ""","result":{"refundedAmount":0}"""))
                reply(orderId, sagaId, "STOCK_CANCEL", replyBody(orderId, sagaId, "STOCK", "CANCEL", "SUCCEEDED", ""","result":{"restoredPrice":400}"""))

                Then("셋이 모두 온 뒤 사가 COMPENSATED · 주문 FAILED 로 닫힌다") {
                    eventually(30.seconds) {
                        orderStatus(orderId) shouldBe "FAILED"
                        val saga = sagaRow(sagaId)
                        saga["status"] shouldBe "COMPENSATED"
                        saga["stock_canceled"] shouldBe true
                        saga["point_canceled"] shouldBe true
                        saga["payment_canceled"] shouldBe true
                    }
                    commands(sagaId).size shouldBe 5
                }
            }
        }

        Given("모양이 맞지 않는 응답") {
            val sagaId = UUID.randomUUID().toString()

            When("깨진 JSON 이 saga.replies 에 오면") {
                reply(999L, sagaId, "POINT_USE", "{not json")

                Then("B-12 재시도 없이 saga.replies-dlt 로 가서 운영자 알림이 울린다") {
                    eventually(30.seconds) {
                        verify(exactly = 1) {
                            alertSender.send(match<ReplyDeadLetterAlert> { it.sagaId == sagaId && it.orderId == "999" && it.messageType == "POINT_USE" })
                        }
                    }
                }
            }
        }
    }
}
