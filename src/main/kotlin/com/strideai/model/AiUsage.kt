package com.strideai.model

import jakarta.persistence.*
import java.time.Instant
import java.time.LocalDate

@Entity
@Table(
    name = "ai_usage",
    uniqueConstraints = [UniqueConstraint(columnNames = ["user_id", "date"])]
)
data class AiUsage(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
    val userId: Long,
    val date: LocalDate,
    var chatCount: Int = 0,
    val createdAt: Instant = Instant.now()
)
