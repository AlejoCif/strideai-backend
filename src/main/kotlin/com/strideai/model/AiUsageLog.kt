package com.strideai.model

import jakarta.persistence.*
import java.time.Instant

@Entity
@Table(name = "ai_usage_log")
data class AiUsageLog(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
    val userId: Long,
    val provider: String,
    val inputTokens: Int,
    val outputTokens: Int,
    val createdAt: Instant = Instant.now()
)
