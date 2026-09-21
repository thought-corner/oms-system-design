package com.project.msa

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class MsaApplication

fun main(args: Array<String>) {
    runApplication<MsaApplication>(*args)
}
