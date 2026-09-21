package com.project.msa.repository

import com.project.msa.DbTag
import com.project.msa.fixture.PaymentFixture
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.extensions.spring.SpringTestLifecycleMode
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

        Given("주문 77 의 결제가 이미 저장된 상태") {
            paymentRepository.saveAndFlush(PaymentFixture.payment(orderId = 77L))

            When("같은 주문 77 로 결제를 한 번 더 저장하면") {
                val exception = shouldThrow<DataIntegrityViolationException> {
                    paymentRepository.saveAndFlush(PaymentFixture.payment(orderId = 77L))
                }

                Then("NFR-2 orderId unique 제약이 두 번째 결제를 막는다") {
                    exception.mostSpecificCause.message shouldContain "Duplicate entry '77'"
                }
            }
        }
    }
}
