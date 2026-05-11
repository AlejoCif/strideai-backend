package com.strideai.dto

// ── Strava API responses ──────────────────────────────────

data class StravaTokenResponse(
    val access_token: String,
    val refresh_token: String,
    val expires_at: Long,
    val athlete: StravaAthleteDto? = null
)

data class StravaAthleteDto(
    val id: Long,
    val firstname: String,
    val lastname: String,
    val profile: String?,
    val city: String?,
    val country: String?
)

data class StravaActivityDto(
    val id: Long,
    val name: String,
    val type: String,
    val sport_type: String,
    val distance: Double,
    val moving_time: Int,
    val elapsed_time: Int,
    val total_elevation_gain: Double,
    val average_speed: Double,
    val max_speed: Double,
    val average_heartrate: Double?,
    val max_heartrate: Double?,
    val average_watts: Double?,
    val weighted_average_watts: Int?,
    val average_cadence: Double? = null,
    val kilojoules: Double? = null,
    val calories: Double? = null,
    val device_watts: Boolean? = null,
    val start_date: String,
    val start_date_local: String,
    val timezone: String?,
    val kudos_count: Int,
    val achievement_count: Int,
    val pr_count: Int,
    val suffer_score: Int?
)

// ── App API responses ─────────────────────────────────────

data class ActivitySummary(
    val stravaId: Long,
    val name: String,
    val type: String,
    val date: String,
    val distanceKm: Double,
    val movingTimeFormatted: String,
    val elevationGain: Double,
    val avgHeartrate: Double?,
    val avgWatts: Double?,
    val avgCadence: Double? = null,
    val calories: Double? = null,
    val estimatedZone: String? = null,
    val tss: Int?
)

data class AthleteProfile(
    val stravaId: Long,
    val name: String,
    val profileImageUrl: String?,
    val city: String?,
    val country: String?
)

data class WeeklyStats(
    val weekLabel: String,
    val totalDistanceKm: Double,
    val totalTimeHours: Double,
    val totalElevation: Double,
    val activityCount: Int,
    val estimatedTss: Int
)

data class FitnessMetrics(
    val ctl: Double,
    val atl: Double,
    val tsb: Double,
    val weeklyTss: Int
)

// ── AI ────────────────────────────────────────────────────

data class ChatRequest(
    val message: String,
    val conversationHistory: List<ChatMessage> = emptyList()
)

data class ChatMessage(
    val role: String,
    val content: String
)

data class ChatResponse(
    val reply: String
)

data class GeneratePlanRequest(
    val goal: String? = null,
    val weeksToEvent: Int? = null
)

data class AnthropicRequest(
    val model: String,
    val max_tokens: Int,
    val system: String,
    val messages: List<ChatMessage>
)

data class AnthropicResponse(
    val content: List<AnthropicContent>
)

data class AnthropicContent(
    val type: String,
    val text: String?
)

// Plan card for history list — GET /api/ai/plan/history
data class TrainingPlanSummary(
    val id: Long,
    val title: String,
    val weekStartDate: String,
    val weekTSS: Int?,
    val createdAt: String,
    val plan: List<TrainingDay>,
    val focus: String,
    val recommendations: List<String>
)

// Structured plan returned by POST /api/ai/plan
data class TrainingPlanData(
    val plan: List<TrainingDay>,
    val weekTSS: Int,
    val focus: String,
    val recommendations: List<String>
)

data class TrainingDay(
    val day: String,
    val type: String,
    val label: String,
    val duration: Int?,
    val zone: String?,
    val note: String
)

// Saved plan record returned by GET /api/ai/plan/latest
data class TrainingPlanResponse(
    val id: Long,
    val planJson: String,
    val weekTss: Int?,
    val focus: String?,
    val weekStartDate: String,
    val createdAt: String
)

data class SyncResponse(
    val synced: Int,
    val success: Boolean,
    val zonesUpdated: Int = 0
)

data class ErrorResponse(
    val error: String,
    val message: String,
    val timestamp: String
)
