package com.project.product

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.nulls.shouldNotBeNull
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext

@SpringBootTest
class ProductApplicationTests : BehaviorSpec() {

    @Autowired
    lateinit var applicationContext: ApplicationContext

    init {
        tags(DbTag)
        extensions(SpringExtension())

        Given("MySQL이 떠 있는 로컬 환경") {
            When("애플리케이션 컨텍스트를 올리면") {
                Then("기동에 성공한다") {
                    applicationContext.shouldNotBeNull()
                }
            }
        }
    }
}
