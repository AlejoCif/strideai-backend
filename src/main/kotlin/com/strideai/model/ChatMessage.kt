package com.strideai.model

import jakarta.persistence.*
import java.time.Instant

@Entity
@Table(name = "chat_messages")
data class ChatMessage(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
    val userId: Long,
    val role: String,
    @Column(columnDefinition = "TEXT")
    val content: String,
    val createdAt: Instant = Instant.now()
)
