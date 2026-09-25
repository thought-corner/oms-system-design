package com.project.point.client

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith

private val RETRY_EXHAUSTED_ALERT =
    DeadLetterAlert("cmd.point", "10", "saga-1", "POINT_USE", "java.lang.IllegalStateException", "db down", DeadLetterKind.RETRY_EXHAUSTED, false)

private val POISON_REPLIED_ALERT =
    DeadLetterAlert("cmd.point", "10", "saga-1", "POINT_USE", "tools.jackson.core.JacksonException", "broken", DeadLetterKind.POISON, true)

private val POISON_UNREPLIED_ALERT = POISON_REPLIED_ALERT.copy(failedReplyWritten = false)

class AlertMessageFormatterTest : BehaviorSpec({

    Given("정방향 요청을 처리할 수 없는데 실패 응답도 쓰지 못한 알림") {
        When("메시지로 만들면") {
            val message = AlertMessageFormatter.format(POISON_UNREPLIED_ALERT)

            Then("즉시 확인 머리말로 주문이 멈췄을 수 있고 어드민이 직접 정리해야 한다고 먼저 적는다") {
                message shouldStartWith "🔴 [즉시 확인] 주문 10의 포인트 사용 요청을 처리할 수 없어 멈췄습니다"
                message shouldContain "자동 취소도 시작되지 못했습니다"
                message shouldContain "어드민 수동 처리가 필요합니다. 주문 상태와 포인트 잔액을 직접 확인해"
            }
        }
    }

    Given("되돌리는 요청을 처리할 수 없는 알림") {
        When("메시지로 만들면") {
            val message = AlertMessageFormatter.format(POISON_UNREPLIED_ALERT.copy(messageType = "POINT_CANCEL"))

            Then("되돌려지지 않은 채 남아 있을 수 있으니 수동으로 되돌리라고 적는다") {
                message shouldStartWith "🔴 [즉시 확인] 주문 10의 포인트 환불 요청을 처리할 수 없어 멈췄습니다"
                message shouldContain "포인트가 차감된 채 남아 있을 수 있습니다"
                message shouldContain "직접 확인해 수동으로 되돌리고"
            }
        }
    }

    Given("어떤 요청인지 알 수 없고 주문 번호도 없는 알림") {
        When("메시지로 만들면") {
            val message = AlertMessageFormatter.format(POISON_UNREPLIED_ALERT.copy(messageType = null, orderId = null))

            Then("알 수 없는 요청이라 자동으로 되돌릴 수 없다고 적는다") {
                message shouldStartWith "🔴 [즉시 확인] 주문 (번호 없음)의 요청을 처리할 수 없어 멈췄습니다"
                message shouldContain "어떤 처리인지 알 수 없는 요청이라"
            }
        }
    }

    Given("실패 응답을 쓴 정방향 요청의 알림") {
        When("메시지로 만들면") {
            val message = AlertMessageFormatter.format(POISON_REPLIED_ALERT)

            Then("결함 머리말로 주문은 실패 처리되고 되돌릴 것이 없다고 적는다") {
                message shouldStartWith "🟡 [결함] 주문 10의 포인트 사용 요청이 잘못되어 주문을 실패 처리했습니다"
                message shouldContain "따로 되돌릴 것은 없습니다"
                message shouldNotContain "어드민 수동 처리가 필요합니다"
            }
        }
    }

    Given("정방향 요청이 재처리를 모두 실패한 알림") {
        When("메시지로 만들면") {
            val message = AlertMessageFormatter.format(RETRY_EXHAUSTED_ALERT)

            Then("장애 머리말로 아직 바뀐 것이 없고 복구되면 이어진다고 적는다") {
                message shouldStartWith "🟠 [장애] 주문 10의 포인트 사용이 시스템 장애로 멈췄습니다"
                message shouldContain "포인트는 아직 차감되지 않았습니다"
                message shouldContain "자동으로 이어서 처리되므로"
            }
        }
    }

    Given("되돌리는 요청이 재처리를 모두 실패한 알림") {
        When("메시지로 만들면") {
            val message = AlertMessageFormatter.format(RETRY_EXHAUSTED_ALERT.copy(messageType = "POINT_CANCEL"))

            Then("되돌려지지 않은 채 남아 있을 수 있고 복구되면 자동으로 되돌려진다고 적는다") {
                message shouldStartWith "🟠 [장애] 주문 10의 포인트 환불이 시스템 장애로 멈췄습니다"
                message shouldContain "포인트가 차감된 채 남아 있을 수 있습니다"
                message shouldContain "포인트는 자동으로 환불되므로"
            }
        }
    }

    Given("어떤 요청인지 알 수 없는데 재처리를 모두 실패한 알림") {
        When("메시지로 만들면") {
            val message = AlertMessageFormatter.format(RETRY_EXHAUSTED_ALERT.copy(messageType = "UNKNOWN"))

            Then("처리가 멈췄다고만 적는다") {
                message shouldStartWith "🟠 [장애] 주문 10의 처리가 시스템 장애로 멈췄습니다"
            }
        }
    }

    Given("예외 메시지가 긴 알림") {
        When("메시지로 만들면") {
            val message = AlertMessageFormatter.format(RETRY_EXHAUSTED_ALERT.copy(exceptionMessage = "x".repeat(600)))

            Then("설명 뒤에 개발자용 로그 블록을 붙이고 예외 메시지는 한도에서 자른다") {
                message shouldContain "\n\n[개발자용 로그]\n```\nkind=RETRY_EXHAUSTED\nfailedReplyWritten=false\ntopic=cmd.point\norderId=10\nsagaId=saga-1\nmessageType=POINT_USE\nexception=java.lang.IllegalStateException\n"
                message shouldContain "message=" + "x".repeat(AlertMessageFormatter.EXCEPTION_MESSAGE_LIMIT) + "…\n```"
                message shouldNotContain "x".repeat(AlertMessageFormatter.EXCEPTION_MESSAGE_LIMIT + 1)
            }
        }
    }
})
