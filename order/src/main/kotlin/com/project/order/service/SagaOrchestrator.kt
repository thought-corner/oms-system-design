package com.project.order.service

import com.project.order.client.AlertSender
import com.project.order.client.PaymentApiClient
import com.project.order.client.PointApiClient
import com.project.order.client.ProductApiClient
import com.project.order.client.RemoteCallPolicy
import com.project.order.client.dto.BuyApiRequest
import com.project.order.client.dto.BuyCancelApiRequest
import com.project.order.client.dto.PayApiRequest
import com.project.order.client.dto.PayCancelApiRequest
import com.project.order.client.dto.UseApiRequest
import com.project.order.client.dto.UseCancelApiRequest
import com.project.order.service.dto.PlaceOrderCommand
import com.project.order.service.dto.SagaContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class SagaOrchestrator(
    private val sagaState: OrderSagaStateService,
    private val productApiClient: ProductApiClient,
    private val pointApiClient: PointApiClient,
    private val paymentApiClient: PaymentApiClient,
    private val alertSender: AlertSender,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun placeOrder(command: PlaceOrderCommand) {
        val context = sagaState.start(command.orderId) ?: return
        run(context)
    }

    fun run(context: SagaContext) {
        try {
            forward(context)
            sagaState.succeed(context.sagaId, context.orderId)
        } catch (e: RuntimeException) {
            compensate(context, e.message, RemoteCallPolicy.COMPENSATION_INLINE_ATTEMPTS)
            throw e
        }
    }

    fun compensate(context: SagaContext, cause: String?, maxAttempts: Int) {
        sagaState.beginCompensation(context.sagaId, cause)

        try {
            paymentApiClient.cancel(PayCancelApiRequest(context.sagaId, context.orderId), maxAttempts)
            pointApiClient.cancel(UseCancelApiRequest(context.sagaId, context.orderId), maxAttempts)
            productApiClient.cancel(BuyCancelApiRequest(context.sagaId, context.orderId), maxAttempts)

            sagaState.compensated(context.sagaId, context.orderId)
        } catch (e: RuntimeException) {
            log.error("Compensation failed: sagaId={}, orderId={}", context.sagaId, context.orderId, e)
            alertSender.send(sagaState.compensationFailed(context.sagaId, e.message))
        }
    }

    private fun forward(context: SagaContext) {
        val totalPrice = productApiClient.buy(
            BuyApiRequest(
                sagaId = context.sagaId,
                orderId = context.orderId,
                items = context.items.map { BuyApiRequest.Item(it.productId, it.quantity) },
            ),
        )
        sagaState.stockCompleted(context.sagaId, totalPrice)

        pointApiClient.use(
            UseApiRequest(context.sagaId, context.orderId, context.userId, totalPrice),
        )
        sagaState.pointCompleted(context.sagaId)

        paymentApiClient.pay(
            PayApiRequest(context.sagaId, context.orderId, context.userId, totalPrice),
        )
        sagaState.paymentCompleted(context.sagaId)
    }

}
