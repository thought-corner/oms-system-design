package com.project.operation.service.policy

import com.project.operation.client.dto.PublishOutcome
import com.project.operation.client.dto.UnpublishedKind
import com.project.operation.domain.OutboxMessage
import com.project.operation.domain.PublishFailure
import com.project.operation.fixture.OutboxFixture.message
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

private val ACKED = PublishOutcome.Acked
private val RETRIABLE = PublishOutcome.Unpublished(UnpublishedKind.RETRIABLE_FAILURE, "TimeoutException: Expiring 1 record(s)")
private val PERMANENT = PublishOutcome.Unpublished(UnpublishedKind.PERMANENT_FAILURE, "RecordTooLargeException: too large")
private val NOT_SENT = PublishOutcome.Unpublished(UnpublishedKind.NOT_SENT, "deadline exceeded before send")

private class CountingCase(
    val batch: String,
    val verdict: String,
    val outcomes: List<PublishOutcome>,
    val expected: (List<OutboxMessage>) -> PublishVerdict,
)

private val cases = listOf(
    CountingCase("빈 배치", "실패 없음", emptyList()) { PublishVerdict.AllAcked },
    CountingCase("전부 확인", "실패 없음", listOf(ACKED, ACKED, ACKED)) { PublishVerdict.AllAcked },
    CountingCase("확인 0건 + 재시도 가능만", "브로커 장애 · 안 셈", listOf(RETRIABLE, RETRIABLE)) { m ->
        PublishVerdict.BrokerUnreachable(unpublished = 2, firstMessage = m[0], cause = RETRIABLE.error)
    },
    CountingCase("확인 0건 + 보내지 못함 뒤 재시도 가능", "브로커 장애 · 원인은 첫 재시도 가능 행", listOf(NOT_SENT, RETRIABLE, NOT_SENT)) { m ->
        PublishVerdict.BrokerUnreachable(unpublished = 3, firstMessage = m[1], cause = RETRIABLE.error)
    },
    CountingCase("확인 있음 + 재시도 가능", "재시도 가능도 셈", listOf(ACKED, RETRIABLE, ACKED)) { m ->
        PublishVerdict.Counted(listOf(PublishFailure(m[1], RETRIABLE.error)), uncounted = 0, firstMessage = m[1], firstError = RETRIABLE.error)
    },
    CountingCase("확인 있음 + 재시도 가능 + 보내지 못함", "보내지 못한 행만 안 셈", listOf(RETRIABLE, NOT_SENT, ACKED)) { m ->
        PublishVerdict.Counted(listOf(PublishFailure(m[0], RETRIABLE.error)), uncounted = 1, firstMessage = m[0], firstError = RETRIABLE.error)
    },
    CountingCase("확인 0건 + 재시도 가능 + 영구 실패", "영구 실패만 셈", listOf(RETRIABLE, PERMANENT)) { m ->
        PublishVerdict.Counted(listOf(PublishFailure(m[1], PERMANENT.error)), uncounted = 1, firstMessage = m[0], firstError = RETRIABLE.error)
    },
    CountingCase("확인 0건 + 영구 실패만", "영구 실패는 늘 셈", listOf(PERMANENT)) { m ->
        PublishVerdict.Counted(listOf(PublishFailure(m[0], PERMANENT.error)), uncounted = 0, firstMessage = m[0], firstError = PERMANENT.error)
    },
    CountingCase("확인 있음 + 영구 실패", "영구 실패를 셈", listOf(ACKED, PERMANENT)) { m ->
        PublishVerdict.Counted(listOf(PublishFailure(m[1], PERMANENT.error)), uncounted = 0, firstMessage = m[1], firstError = PERMANENT.error)
    },
    CountingCase("확인 0건 + 보내지 못함만", "브로커 장애가 아니고 셀 것도 없어 보내지 못함으로 둠", listOf(NOT_SENT, NOT_SENT)) { m ->
        PublishVerdict.Unsent(unsent = 2, firstMessage = m[0], reason = NOT_SENT.error)
    },
    CountingCase("확인 있음 + 보내지 못함만", "셀 것이 없어 보내지 못함으로 둠", listOf(ACKED, NOT_SENT)) { m ->
        PublishVerdict.Unsent(unsent = 1, firstMessage = m[1], reason = NOT_SENT.error)
    },
)

class PublishFailureCountingTest : BehaviorSpec({

    cases.forEach { case ->
        Given("${case.batch} 배치 ${case.outcomes.map { (it as? PublishOutcome.Unpublished)?.kind ?: ACKED }}") {
            val messages = case.outcomes.indices.map { message(it + 1L) }

            When("행 실패로 셀지 판정하면") {
                val verdict = PublishFailureCounting.classify(messages.zip(case.outcomes))

                Then(case.verdict) {
                    verdict shouldBe case.expected(messages)
                }
            }
        }
    }
})
