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
        config.connectionTimeout = 30000
        config.maximumPoolSize = 3

        val dbUrl = System.getenv("DATABASE_URL")

        if (!dbUrl.isNullOrBlank()) {
            try {
                // Normalize URL: strip jdbc: prefix, normalize postgres:// to postgresql://
                val normalized = dbUrl
                    .let { if (it.startsWith("jdbc:")) it.substring(5) else it }
                    .let { if (it.startsWith("postgres://")) it.replaceFirst("postgres://", "postgresql://") else it }

                val uri = URI(normalized)
                val host = uri.host
                val port = if (uri.port > 0) uri.port else 5432
                val database = uri.path?.trimStart('/') ?: ""

                if (!host.isNullOrBlank() && database.isNotBlank()) {
                    val userInfo = uri.userInfo?.split(":", limit = 2)
                    config.jdbcUrl = "jdbc:postgresql://$host:$port/$database"
                    config.username = userInfo?.getOrNull(0) ?: System.getenv("PGUSER") ?: "postgres"
                    config.password = userInfo?.getOrNull(1) ?: System.getenv("PGPASSWORD") ?: ""
                    config.addDataSourceProperty("ssl", "true")
                    config.addDataSourceProperty("sslmode", "require")
                    return HikariDataSource(config)
                }
            } catch (_: Exception) {
                // fall through to PG* vars
            }
        }

        // Fall back to individual PG* environment variables
        val host = System.getenv("PGHOST") ?: "localhost"
        val port = System.getenv("PGPORT") ?: "5432"
        val database = System.getenv("PGDATABASE") ?: "strideai"
        config.jdbcUrl = "jdbc:postgresql://$host:$port/$database"
        config.username = System.getenv("PGUSER") ?: "postgres"
        config.password = System.getenv("PGPASSWORD") ?: "postgres"

        return HikariDataSource(config)
    }
}
