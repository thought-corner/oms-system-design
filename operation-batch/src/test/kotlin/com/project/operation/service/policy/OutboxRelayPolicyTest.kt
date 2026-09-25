package com.project.operation.service.policy

import com.project.operation.client.KafkaProducerPolicy
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.comparables.shouldBeLessThan
import java.time.Duration

class OutboxRelayPolicyTest : BehaviorSpec({

    Given("프로듀서의 전달 제한 시간, 릴레이의 발행 마감, 행 임대") {
        val deliveryTimeout = KafkaProducerPolicy.DELIVERY_TIMEOUT
        val publishDeadline = OutboxRelayPolicy.PUBLISH_DEADLINE
        val claimLease = Duration.ofSeconds(OutboxRelayPolicy.CLAIM_LEASE_SECONDS)

        Then("브로커 확인을 기다리는 동안 전달 제한이 먼저 끝나 결과를 받는다") {
            deliveryTimeout shouldBeLessThan publishDeadline
        }

        Then("발행 마감이 임대보다 먼저 끝나 다른 릴레이가 같은 행을 다시 집기 전에 결과를 기록한다") {
            publishDeadline shouldBeLessThan claimLease
        }
    }
})
