package com.project.order

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication(scanBasePackages = ["com.project.order", "com.project.common"])
@EnableScheduling
class OrderApplication

fun main(args: Array<String>) {
    runApplication<OrderApplication>(*args)
}
