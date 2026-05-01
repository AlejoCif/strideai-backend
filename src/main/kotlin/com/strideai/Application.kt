package com.strideai

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableScheduling
class StrideAiApplication

fun main(args: Array<String>) {
    runApplication<StrideAiApplication>(*args)
}
