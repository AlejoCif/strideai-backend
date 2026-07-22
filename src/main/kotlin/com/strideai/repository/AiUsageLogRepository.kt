package com.strideai.repository

import com.strideai.model.AiUsageLog
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

@Repository
interface AiUsageLogRepository : JpaRepository<AiUsageLog, Long> {

    @Query("SELECT COALESCE(SUM(a.inputTokens), 0) FROM AiUsageLog a WHERE a.provider = :provider")
    fun sumInputTokens(@Param("provider") provider: String): Long

    @Query("SELECT COALESCE(SUM(a.outputTokens), 0) FROM AiUsageLog a WHERE a.provider = :provider")
    fun sumOutputTokens(@Param("provider") provider: String): Long
}
