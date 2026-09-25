package com.project.point.client

import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.core.spec.style.BehaviorSpec
import org.hamcrest.Matchers.containsString
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.test.web.client.ExpectedCount
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.web.client.RestClient

private const val ACTION_URL = "https://discord.test/api/webhooks/1/action"
private const val INCIDENT_URL = "https://discord.test/api/webhooks/2/incident"
private const val DEFECT_URL = "https://discord.test/api/webhooks/3/defect"

private val POISON_REPLIED_ALERT =
    DeadLetterAlert("point.command", "10", "saga-1", "POINT_USE", "tools.jackson.core.JacksonException", "broken", DeadLetterKind.POISON, true)

private val POISON_UNREPLIED_ALERT = POISON_REPLIED_ALERT.copy(messageType = "POINT_CANCEL", failedReplyWritten = false)

private val RETRY_EXHAUSTED_ALERT =
    DeadLetterAlert("point.command", "10", "saga-1", "POINT_USE", "java.lang.IllegalStateException", "db down", DeadLetterKind.RETRY_EXHAUSTED, false)

class DiscordAlertSenderTest : BehaviorSpec({

    Given("실패 응답을 쓰지 못한 POISON 커맨드와 세 웹훅이 설정된 알림 발송기") {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        val sender = DiscordAlertSender(ACTION_URL, INCIDENT_URL, DEFECT_URL, builder.build())
        server.expect(requestTo(ACTION_URL))
            .andExpect(method(HttpMethod.POST))
            .andExpect(jsonPath("$.content").value(containsString("[즉시 확인]")))
            .andExpect(jsonPath("$.content").value(containsString("[개발자용 로그]")))
            .andExpect(jsonPath("$.content").value(containsString("failedReplyWritten=false")))
            .andExpect(jsonPath("$.allowed_mentions.parse").isEmpty)
            .andRespond(withStatus(HttpStatus.NO_CONTENT))

        When("알림을 보내면") {
            sender.send(POISON_UNREPLIED_ALERT)

            Then("보상이 시작되지 않았을 수 있어 멘션 없이 action 웹훅에 한 번 보낸다") {
                server.verify()
            }
        }
    }

    Given("실패 응답을 쓴 POISON 커맨드와 세 웹훅이 설정된 알림 발송기") {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        val sender = DiscordAlertSender(ACTION_URL, INCIDENT_URL, DEFECT_URL, builder.build())
        server.expect(requestTo(DEFECT_URL))
            .andExpect(jsonPath("$.content").value(containsString("failedReplyWritten=true")))
            .andRespond(withStatus(HttpStatus.NO_CONTENT))

        When("알림을 보내면") {
            sender.send(POISON_REPLIED_ALERT)

            Then("주문은 보상으로 닫히므로 defect 웹훅에 보낸다") {
                server.verify()
            }
        }
    }

    Given("재처리를 모두 실패한 커맨드와 세 웹훅이 설정된 알림 발송기") {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        val sender = DiscordAlertSender(ACTION_URL, INCIDENT_URL, DEFECT_URL, builder.build())
        server.expect(requestTo(INCIDENT_URL))
            .andExpect(jsonPath("$.content").value(containsString("[장애]")))
            .andRespond(withStatus(HttpStatus.NO_CONTENT))

        When("알림을 보내면") {
            sender.send(RETRY_EXHAUSTED_ALERT)

            Then("RETRY_EXHAUSTED 문구를 incident 웹훅에 보낸다") {
                server.verify()
            }
        }
    }

    Given("예외 메시지가 긴 알림") {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        val sender = DiscordAlertSender(ACTION_URL, INCIDENT_URL, DEFECT_URL, builder.build())
        server.expect(requestTo(INCIDENT_URL))
            .andExpect(jsonPath("$.content").value(containsString("x".repeat(AlertMessageFormatter.EXCEPTION_MESSAGE_LIMIT) + "…")))
            .andRespond(withStatus(HttpStatus.NO_CONTENT))

        When("보내면") {
            sender.send(RETRY_EXHAUSTED_ALERT.copy(exceptionMessage = "x".repeat(3000)))

            Then("예외 메시지를 잘라 디스코드 본문 한도 안에서 보낸다") {
                server.verify()
            }
        }
    }

    Given("웹훅이 실패로 응답하는 알림 발송기") {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        val sender = DiscordAlertSender(ACTION_URL, INCIDENT_URL, DEFECT_URL, builder.build())
        server.expect(ExpectedCount.once(), requestTo(ACTION_URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS))

        When("알림을 보내면") {

            Then("예외를 내지 않아 DLT 핸들러가 다시 돌지 않는다") {
                shouldNotThrowAny { sender.send(POISON_UNREPLIED_ALERT) }
                server.verify()
            }
        }
    }

    Given("웹훅 주소가 모두 비어 있는 알림 발송기") {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        val sender = DiscordAlertSender("", "", "", builder.build())

        When("알림을 보내면") {
            sender.send(POISON_UNREPLIED_ALERT)

            Then("로그만 남기고 웹훅을 부르지 않는다") {
                server.verify()
            }
        }
    }

    Given("action 웹훅만 설정된 알림 발송기") {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        val sender = DiscordAlertSender(ACTION_URL, "", "", builder.build())

        When("재처리를 모두 실패한 커맨드와 실패 응답을 쓴 POISON 의 알림을 보내면") {
            sender.send(RETRY_EXHAUSTED_ALERT)
            sender.send(POISON_REPLIED_ALERT)

            Then("action 채널로 새지 않고 로그만 남긴다") {
                server.verify()
            }
        }
    }
})
