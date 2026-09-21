package com.project.msa.repository

import com.project.msa.DbTag
import com.project.msa.fixture.PointFixture
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
class PointRepositoryTest : BehaviorSpec() {

    @Autowired
    lateinit var pointRepository: PointRepository

    init {
        extensions(SpringExtension(SpringTestLifecycleMode.Root))
        tags(DbTag)

        Given("사용자 777 의 포인트가 이미 저장된 상태") {
            pointRepository.saveAndFlush(PointFixture.point(userId = 777L, id = null))

            When("같은 사용자 777 의 포인트를 한 번 더 저장하면") {
                val exception = shouldThrow<DataIntegrityViolationException> {
                    pointRepository.saveAndFlush(PointFixture.point(userId = 777L, id = null))
                }

                Then("userId unique 제약이 두 번째 포인트를 막는다") {
                    exception.mostSpecificCause.message shouldContain "Duplicate entry '777'"
                }
            }
        }
    }
}
