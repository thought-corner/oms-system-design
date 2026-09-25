package com.project.point.init

import com.project.point.domain.Point
import com.project.point.repository.PointRepository
import io.kotest.core.spec.style.BehaviorSpec
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify

class PointDataCreatorTest : BehaviorSpec({

    Given("포인트 행이 없는 스키마") {
        val pointRepository = mockk<PointRepository>(relaxed = true)
        every { pointRepository.count() } returns 0L
        every { pointRepository.save(any<Point>()) } answers { firstArg() }

        When("시드를 만들면") {
            PointDataCreator(pointRepository).createSeedData()

            Then("시드 사용자마다 포인트 행을 하나씩 넣는다") {
                verify(exactly = PointDataCreator.SEED_USER_IDS.size) { pointRepository.save(any<Point>()) }
            }
        }
    }

    Given("ddl-auto=update 로 다시 띄워 포인트 행이 남아 있는 스키마") {
        val pointRepository = mockk<PointRepository>(relaxed = true)
        every { pointRepository.count() } returns 3L

        When("시드를 만들면") {
            PointDataCreator(pointRepository).createSeedData()

            Then("아무것도 넣지 않는다") {
                verify(exactly = 0) { pointRepository.save(any<Point>()) }
            }
        }
    }
})
