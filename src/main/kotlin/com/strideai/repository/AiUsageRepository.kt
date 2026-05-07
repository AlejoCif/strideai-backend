package com.strideai.repository

import com.strideai.model.AiUsage
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.time.LocalDate

@Repository
interface AiUsageRepository : JpaRepository<AiUsage, Long> {
    fun findByUserIdAndDate(userId: Long, date: LocalDate): AiUsage?
}
