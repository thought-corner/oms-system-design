package com.project.payment.service

import com.project.payment.repository.PaymentRepository
import com.project.payment.service.dto.PayCommand
import org.springframework.stereotype.Component
import java.time.Duration

@Component
class PaymentApproval(
    private val paymentRepository: PaymentRepository,
) {

    fun await(command: PayCommand) {
        if (alreadyDecided(command)) {
            return
        }
        Thread.sleep(EXTERNAL_PAYMENT_GATEWAY_LATENCY.toMillis())
    }

    private fun alreadyDecided(command: PayCommand): Boolean =
        paymentRepository.findBySagaId(command.sagaId) != null ||
            paymentRepository.findByPaidOrderId(command.orderId) != null

    companion object {
        val EXTERNAL_PAYMENT_GATEWAY_LATENCY: Duration = Duration.ofSeconds(3)
    }
}
