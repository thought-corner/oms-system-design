package com.project.operation

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication(scanBasePackages = ["com.project.operation", "com.project.common"])
@EnableScheduling
class OperationBatchApplication

fun main(args: Array<String>) {
    runApplication<OperationBatchApplication>(*args)
}
