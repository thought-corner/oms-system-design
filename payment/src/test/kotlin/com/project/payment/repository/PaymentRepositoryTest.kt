package com.project.payment.repository

import com.project.payment.DbTag
import com.project.payment.domain.Payment
import com.project.payment.domain.PaymentStatus
import com.project.payment.fixture.PaymentFixture
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.extensions.spring.SpringTestLifecycleMode
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.dao.DataIntegrityViolationException

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class PaymentRepositoryTest : BehaviorSpec() {

    @Autowired
    lateinit var paymentRepository: PaymentRepository

    init {
        extensions(SpringExtension(SpringTestLifecycleMode.Root))
        tags(DbTag)

        Given("주문 77의 PAID 결제가 이미 저장된 상태") {
            paymentRepository.saveAndFlush(PaymentFixture.payment(sagaId = "saga-77-a", orderId = 77L))

            When("다른 sagaId로 같은 주문의 PAID 결제를 저장하면") {
                val exception = shouldThrow<DataIntegrityViolationException> {
                    paymentRepository.saveAndFlush(PaymentFixture.payment(sagaId = "saga-77-b", orderId = 77L))
                }

                Then("paid_order_id unique 제약이 주문당 두 번째 PAID를 막는다") {
                    exception.mostSpecificCause.message shouldContain Payment.UK_PAID_ORDER_ID
                }
            }
        }

        Given("사가 saga-78-a의 결제가 이미 저장된 상태") {
            paymentRepository.saveAndFlush(PaymentFixture.payment(sagaId = "saga-78-a", orderId = 78L))

            When("같은 sagaId로 결제를 한 번 더 저장하면") {
                val exception = shouldThrow<DataIntegrityViolationException> {
                    paymentRepository.saveAndFlush(PaymentFixture.payment(sagaId = "saga-78-a", orderId = 780L))
                }

                Then("sagaId unique 제약이 같은 사가의 이중 결제를 막는다") {
                    exception.mostSpecificCause.message shouldContain "Duplicate entry 'saga-78-a'"
                }
            }
        }

        Given("주문 79의 결제가 취소된 상태") {
            val first = paymentRepository.saveAndFlush(PaymentFixture.payment(sagaId = "saga-79-a", orderId = 79L))
            first.transitionTo(PaymentStatus.CANCELED)
            paymentRepository.saveAndFlush(first)

            When("다른 sagaId로 다시 결제하면") {
                val saved = paymentRepository.saveAndFlush(PaymentFixture.payment(sagaId = "saga-79-b", orderId = 79L))

                Then("허용되고 주문의 PAID 결제는 새 결제 하나다") {
                    saved.id.shouldNotBeNull()
                    paymentRepository.findByPaidOrderId(79L)?.id shouldBe saved.id
                }
            }
        }
    }
}
