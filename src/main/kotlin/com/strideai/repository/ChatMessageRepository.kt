package com.strideai.repository

import com.strideai.model.ChatMessage
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

@Repository
interface ChatMessageRepository : JpaRepository<ChatMessage, Long> {
    // Returns the N most recent messages in chronological (ASC) order.
    // The subquery selects DESC to get the newest N, outer query re-orders ASC for correct history.
    @Query(
        value = "SELECT * FROM (SELECT * FROM chat_messages WHERE user_id = :userId ORDER BY id DESC LIMIT :limit) sub ORDER BY id ASC",
        nativeQuery = true
    )
    fun findRecentByUserIdOrderedAsc(@Param("userId") userId: Long, @Param("limit") limit: Int): List<ChatMessage>

    fun countByUserId(userId: Long): Long

    @Modifying
    @Transactional
    @Query("DELETE FROM ChatMessage m WHERE m.userId = :userId")
    fun deleteAllByUserId(userId: Long)
}
