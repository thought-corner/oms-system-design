package com.project.payment.service

import com.project.payment.DbTag
import com.project.payment.domain.PaymentStatus
import com.project.payment.fixture.PaymentFixture
import com.project.payment.repository.OutboxMessageRepository
import com.project.payment.repository.PaymentRepository
import com.project.payment.repository.SagaGuardRepository
import com.project.payment.service.dto.PayCancelCommand
import com.project.payment.service.dto.PayCommand
import com.project.payment.statemachine.PaymentStateMachine
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.extensions.spring.SpringTestLifecycleMode
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private data class PayCancelRace(
    val waited: Boolean,
    val status: PaymentStatus?,
    val paidOrderId: Long?,
    val replyTypes: List<String>,
)

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class PaymentServiceConcurrencyTest : BehaviorSpec() {

    @Autowired
    lateinit var paymentRepository: PaymentRepository

    @Autowired
    lateinit var guardRepository: SagaGuardRepository

    @Autowired
    lateinit var outboxMessageRepository: OutboxMessageRepository

    @Autowired
    lateinit var transactionManager: PlatformTransactionManager

    private fun newTransaction(): TransactionTemplate =
        TransactionTemplate(transactionManager).apply {
            propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
        }

    init {
        extensions(SpringExtension(SpringTestLifecycleMode.Root))
        tags(DbTag)

        Given("결제가 가드를 쥔 채 아직 커밋하지 않은 사가") {
            val sagaId = "saga-concurrency-payment"
            val orderId = 9001L
            val service = PaymentService(
                paymentRepository,
                SagaGuardLock(guardRepository, PaymentFixture.FIXED_CLOCK),
                PaymentStateMachine(),
                SagaReplyOutbox(outboxMessageRepository, PaymentFixture.FIXED_CLOCK),
                PaymentFixture.FIXED_CLOCK,
            )
            val executor = Executors.newFixedThreadPool(2)
            val locked = CountDownLatch(1)
            val release = CountDownLatch(1)

            When("같은 사가의 보상이 도착하면") {
                val result = try {
                    val payer = executor.submit {
                        newTransaction().execute {
                            service.pay(PayCommand(sagaId, orderId, PaymentFixture.DEFAULT_USER_ID, PaymentFixture.DEFAULT_AMOUNT))
                            locked.countDown()
                            release.await(10, TimeUnit.SECONDS)
                        }
                    }
                    locked.await(10, TimeUnit.SECONDS) shouldBe true
                    val canceller = executor.submit { newTransaction().execute { service.cancel(PayCancelCommand(sagaId, orderId)) } }
                    val waited = try {
                        Thread.sleep(500)
                        !canceller.isDone
                    } finally {
                        release.countDown()
                    }
                    payer.get(10, TimeUnit.SECONDS)
                    canceller.get(10, TimeUnit.SECONDS)
                    val payment = newTransaction().execute { paymentRepository.findBySagaId(sagaId) }
                    val replyTypes = newTransaction().execute { outboxMessageRepository.findAllBySagaId(sagaId).map { it.messageType } }
                    PayCancelRace(waited, payment?.status, payment?.paidOrderId, replyTypes.orEmpty())
                } finally {
                    executor.shutdownNow()
                    newTransaction().execute {
                        paymentRepository.findBySagaId(sagaId)?.let { paymentRepository.delete(it) }
                        guardRepository.deleteById(sagaId)
                        outboxMessageRepository.deleteAll(outboxMessageRepository.findAllBySagaId(sagaId))
                    }
                }

                Then("보상은 가드에서 기다렸다가 커밋된 결제를 보고 취소하고 두 응답이 각자 커밋된다") {
                    result.waited shouldBe true
                    result.status shouldBe PaymentStatus.CANCELED
                    result.paidOrderId.shouldBeNull()
                    result.replyTypes shouldContainExactlyInAnyOrder listOf("PAYMENT_PAY", "PAYMENT_CANCEL")
                }
            }
        }
    }
}
