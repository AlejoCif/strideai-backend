package com.strideai.service

import com.strideai.repository.AiUsageRepository
import com.strideai.repository.UserRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.time.LocalDate

@Service
class AiUsageService(
    private val aiUsageRepository: AiUsageRepository,
    private val userRepository: UserRepository
) {
    @Value("\${app.admin-strava-id}") private var adminStravaId: Long = 0

    private val CHAT_LIMIT = 10

    fun checkAndIncrementChat(userId: Long): Boolean {
        if (isAdmin(userId)) return true
        return aiUsageRepository.incrementIfUnderLimit(userId, LocalDate.now(), CHAT_LIMIT) > 0
    }

    fun getUsage(userId: Long): Map<String, Any> {
        val admin = isAdmin(userId)
        val today = LocalDate.now()
        val usage = aiUsageRepository.findByUserIdAndDate(userId, today)
        val chatUsed = usage?.chatCount ?: 0
        val limit = if (admin) 999 else CHAT_LIMIT

        return mapOf(
            "chatUsed" to chatUsed,
            "chatLimit" to limit,
            "chatRemaining" to maxOf(0, limit - chatUsed),
            "isAdmin" to admin,
            "resetsAt" to today.plusDays(1).atStartOfDay().toString()
        )
    }

    private fun isAdmin(userId: Long): Boolean =
        userRepository.findById(userId).orElse(null)?.stravaId == adminStravaId
}
