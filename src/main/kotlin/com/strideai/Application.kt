package com.strideai

import org.springframework.boot.CommandLineRunner
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.scheduling.annotation.EnableScheduling
import java.net.InetAddress

@SpringBootApplication(exclude = [UserDetailsServiceAutoConfiguration::class])
@EnableScheduling
class StrideAiApplication {

    @Bean
    fun debugEnv(): CommandLineRunner {
        return CommandLineRunner {
            println("=== DEBUG DB CONFIG ===")
            println("SPRING_DATASOURCE_URL=" + System.getenv("SPRING_DATASOURCE_URL"))
            println("PGHOST=" + System.getenv("PGHOST"))
            println("PGPORT=" + System.getenv("PGPORT"))
            println("PGDATABASE=" + System.getenv("PGDATABASE"))
            println("PGUSER=" + System.getenv("PGUSER"))

            try {
                println("PGHOST_RESOLVED=" + InetAddress.getByName(System.getenv("PGHOST")))
            } catch (e: Exception) {
                println("PGHOST_RESOLVE_ERROR=" + e.message)
            }

            println("=======================")
        }
    }
}

fun main(args: Array<String>) {
    runApplication<StrideAiApplication>(*args)
}