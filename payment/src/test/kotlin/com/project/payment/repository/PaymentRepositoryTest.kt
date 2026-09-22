package com.project.payment.repository

import com.project.payment.DbTag
import com.project.payment.fixture.PaymentFixture
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.extensions.spring.SpringTestLifecycleMode
import io.kotest.matchers.nulls.shouldNotBeNull
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

        Given("사가 saga-77-a의 결제가 이미 저장된 상태") {
            paymentRepository.saveAndFlush(PaymentFixture.payment(sagaId = "saga-77-a", orderId = 77L))

            When("다른 sagaId로 같은 주문을 저장하면") {
                val saved = paymentRepository.saveAndFlush(PaymentFixture.payment(sagaId = "saga-77-b", orderId = 77L))

                Then("허용된다 — 제약은 주문당 1건이 아니라 주문당 PAID 1건이고 그 판정은 서비스가 한다") {
                    saved.id.shouldNotBeNull()
                }
            }

            When("같은 sagaId로 결제를 한 번 더 저장하면") {
                val exception = shouldThrow<DataIntegrityViolationException> {
                    paymentRepository.saveAndFlush(PaymentFixture.payment(sagaId = "saga-77-a", orderId = 77L))
                }

                Then("sagaId unique 제약이 같은 사가의 이중 결제를 막는다") {
                    exception.mostSpecificCause.message shouldContain "Duplicate entry 'saga-77-a'"
                }
            }
        }
    }
}
