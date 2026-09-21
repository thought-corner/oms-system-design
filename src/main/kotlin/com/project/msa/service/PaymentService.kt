package com.project.msa.service

import com.project.msa.domain.Payment
import com.project.msa.repository.PaymentRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.LocalDateTime

@Service
class PaymentService(
    private val paymentRepository: PaymentRepository,
    private val pointService: PointService,
    private val clock: Clock,
) {

    @Transactional
    fun pay(orderId: Long, userId: Long, amount: Long): Payment {
        pointService.use(userId, amount)
        simulateExternalPaymentGatewayLatency()

        return paymentRepository.save(
            Payment(orderId = orderId, userId = userId, amount = amount, paidAt = LocalDateTime.now(clock)),
        )
    }

    private fun simulateExternalPaymentGatewayLatency() {
        Thread.sleep(EXTERNAL_PAYMENT_GATEWAY_LATENCY.toMillis())
    }

    companion object {
        val EXTERNAL_PAYMENT_GATEWAY_LATENCY: Duration = Duration.ofSeconds(3)
    }
}
