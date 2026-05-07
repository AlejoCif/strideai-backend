package com.strideai.repository

import com.strideai.model.ChatMessage
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

@Repository
interface ChatMessageRepository : JpaRepository<ChatMessage, Long> {
    fun findTop50ByUserIdOrderByCreatedAtAsc(userId: Long): List<ChatMessage>
    fun countByUserId(userId: Long): Long

    @Modifying
    @Transactional
    @Query("DELETE FROM ChatMessage m WHERE m.userId = :userId")
    fun deleteAllByUserId(userId: Long)
}
