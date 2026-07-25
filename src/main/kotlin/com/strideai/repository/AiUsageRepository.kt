package com.strideai.repository

import com.strideai.model.AiUsage
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

@Repository
interface AiUsageRepository : JpaRepository<AiUsage, Long> {
    fun findByUserIdAndDate(userId: Long, date: LocalDate): AiUsage?

    // Atomic upsert: inserts a new row (count=1) or increments an existing one,
    // but only if the current count is below the limit. Returns rows affected:
    // 1 = increment succeeded (under limit), 0 = already at or above limit.
    @Modifying
    @Transactional
    @Query(
        value = """
            INSERT INTO ai_usage (user_id, date, chat_count, created_at)
            VALUES (:userId, :date, 1, NOW())
            ON CONFLICT (user_id, date) DO UPDATE
              SET chat_count = ai_usage.chat_count + 1
            WHERE ai_usage.chat_count < :limit
        """,
        nativeQuery = true
    )
    fun incrementIfUnderLimit(
        @Param("userId") userId: Long,
        @Param("date") date: LocalDate,
        @Param("limit") limit: Int
    ): Int
}
