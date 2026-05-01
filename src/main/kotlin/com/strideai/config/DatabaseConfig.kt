package com.strideai.config

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import java.net.URI
import javax.sql.DataSource

@Configuration
class DatabaseConfig {

    @Bean
    @Primary
    fun dataSource(): DataSource {
        val config = HikariConfig()
        val databaseUrl = System.getenv("DATABASE_URL")

        if (databaseUrl != null) {
            val uri = URI(databaseUrl)
            val userInfo = uri.userInfo.split(":", limit = 2)
            config.jdbcUrl = "jdbc:postgresql://${uri.host}:${uri.port}${uri.path}"
            config.username = userInfo[0]
            config.password = if (userInfo.size > 1) userInfo[1] else ""
            config.addDataSourceProperty("ssl", "true")
            config.addDataSourceProperty("sslmode", "require")
        } else {
            val host = System.getenv("PGHOST") ?: "localhost"
            val port = System.getenv("PGPORT") ?: "5432"
            val database = System.getenv("PGDATABASE") ?: "strideai"
            config.jdbcUrl = "jdbc:postgresql://$host:$port/$database"
            config.username = System.getenv("PGUSER") ?: "postgres"
            config.password = System.getenv("PGPASSWORD") ?: "postgres"
        }

        config.connectionTimeout = 30000
        config.maximumPoolSize = 3

        return HikariDataSource(config)
    }
}
