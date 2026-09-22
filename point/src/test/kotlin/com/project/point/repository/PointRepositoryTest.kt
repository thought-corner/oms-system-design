package com.project.point.repository

import com.project.point.DbTag
import com.project.point.fixture.PointFixture
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.extensions.spring.SpringTestLifecycleMode
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
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

        Given("사용자 888의 포인트") {
            val saved = pointRepository.saveAndFlush(PointFixture.point(userId = 888L, id = null))

            When("행 락으로 읽으면") {
                val found = pointRepository.findWithLockByUserId(888L)

                Then("같은 포인트를 돌려준다") {
                    found?.id shouldBe saved.id
                }
            }

            When("없는 사용자를 행 락으로 읽으면") {
                val found = pointRepository.findWithLockByUserId(999_999L)

                Then("null을 돌려준다") {
                    found.shouldBeNull()
                }
            }
        }

        Given("사용자 777의 포인트가 이미 저장된 상태") {
            pointRepository.saveAndFlush(PointFixture.point(userId = 777L, id = null))

            When("같은 사용자 777의 포인트를 한 번 더 저장하면") {
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
