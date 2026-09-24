package com.project.point

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication(scanBasePackages = ["com.project.point", "com.project.common"])
class PointApplication

fun main(args: Array<String>) {
    runApplication<PointApplication>(*args)
}
