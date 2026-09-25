package com.project.operation

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableScheduling
class OperationBatchApplication

fun main(args: Array<String>) {
    runApplication<OperationBatchApplication>(*args)
}
