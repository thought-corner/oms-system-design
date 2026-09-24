package com.project.product

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication(scanBasePackages = ["com.project.product", "com.project.common"])
class ProductApplication

fun main(args: Array<String>) {
    runApplication<ProductApplication>(*args)
}
