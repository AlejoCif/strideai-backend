package com.strideai.service

import com.strideai.dto.*
import com.strideai.model.Activity
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToMono
import java.time.Instant
import java.time.ZonedDateTime
import kotlin.math.roundToInt

@Service
class StravaService(
    private val webClient: WebClient
) {
    @Value("\${app.strava.client-id}") private lateinit var clientId: String
    @Value("\${app.strava.client-secret}") private lateinit var clientSecret: String
    @Value("\${app.strava.refresh-token}") private lateinit var defaultRefreshToken: String
    @Value("\${app.strava.api-base-url}") private lateinit var apiBaseUrl: String
    @Value("\${app.strava.token-url}") private lateinit var tokenUrl: String

    // Simple in-memory token cache (for single-user mode)
    private var cachedAccessToken: String? = null
    private var tokenExpiresAt: Long = 0

    fun updateTokenCache(accessToken: String, refreshToken: String, expiresAt: Long) {
        cachedAccessToken = accessToken
        defaultRefreshToken = refreshToken
        tokenExpiresAt = expiresAt
    }

    // ── Token Management ─────────────────────────────────────

    fun getValidToken(refreshToken: String = defaultRefreshToken): String {
        val now = Instant.now().epochSecond

        // Return cached token if still valid (5 min buffer)
        if (cachedAccessToken != null && tokenExpiresAt > now + 300) {
            return cachedAccessToken!!
        }

        val response = webClient.post()
            .uri(tokenUrl)
            .bodyValue(mapOf(
                "client_id" to clientId,
                "client_secret" to clientSecret,
                "refresh_token" to refreshToken,
                "grant_type" to "refresh_token"
            ))
            .retrieve()
            .bodyToMono<StravaTokenResponse>()
            .block() ?: throw RuntimeException("Failed to refresh Strava token")

        cachedAccessToken = response.access_token
        tokenExpiresAt = response.expires_at

        println("✅ Strava token refreshed, expires: ${Instant.ofEpochSecond(response.expires_at)}")
        return cachedAccessToken!!
    }

    // ── Athlete ──────────────────────────────────────────────

    fun getAthlete(): StravaAthleteDto {
        val token = getValidToken()
        return webClient.get()
            .uri("$apiBaseUrl/athlete")
            .header("Authorization", "Bearer $token")
            .retrieve()
            .bodyToMono<StravaAthleteDto>()
            .block() ?: throw RuntimeException("Failed to fetch athlete")
    }

    // ── Activities ───────────────────────────────────────────

    fun getRecentActivities(perPage: Int = 30, page: Int = 1): List<StravaActivityDto> {
        val token = getValidToken()
        return webClient.get()
            .uri("$apiBaseUrl/athlete/activities?per_page=$perPage&page=$page")
            .header("Authorization", "Bearer $token")
            .retrieve()
            .bodyToMono<List<StravaActivityDto>>()
            .block() ?: emptyList()
    }

    fun getActivitiesSince(epochSeconds: Long): List<StravaActivityDto> {
        val token = getValidToken()
        return webClient.get()
            .uri("$apiBaseUrl/athlete/activities?after=$epochSeconds&per_page=100")
            .header("Authorization", "Bearer $token")
            .retrieve()
            .bodyToMono<List<StravaActivityDto>>()
            .block() ?: emptyList()
    }

    // ── Conversions ──────────────────────────────────────────

    fun toActivitySummary(dto: StravaActivityDto): ActivitySummary {
        return ActivitySummary(
            stravaId = dto.id,
            name = dto.name,
            type = dto.sport_type,
            date = dto.start_date_local.take(10),
            distanceKm = (dto.distance / 1000 * 10).roundToInt() / 10.0,
            movingTimeFormatted = formatSeconds(dto.moving_time),
            elevationGain = dto.total_elevation_gain,
            avgHeartrate = dto.average_heartrate,
            avgWatts = dto.average_watts,
            tss = estimateTss(dto)
        )
    }

    fun toActivity(dto: StravaActivityDto, userId: Long): Activity {
        return Activity(
            stravaId = dto.id,
            userId = userId,
            name = dto.name,
            type = dto.type,
            sportType = dto.sport_type,
            distanceMeters = dto.distance,
            movingTimeSecs = dto.moving_time,
            elapsedTimeSecs = dto.elapsed_time,
            totalElevationGain = dto.total_elevation_gain,
            averageSpeed = dto.average_speed,
            maxSpeed = dto.max_speed,
            averageHeartrate = dto.average_heartrate,
            maxHeartrate = dto.max_heartrate,
            averageWatts = dto.average_watts,
            weightedAverageWatts = dto.weighted_average_watts,
            startDate = ZonedDateTime.parse(dto.start_date).toInstant(),
            startDateLocal = dto.start_date_local,
            timezone = dto.timezone,
            kudosCount = dto.kudos_count,
            achievementCount = dto.achievement_count,
            prCount = dto.pr_count,
            sufferScore = dto.suffer_score
        )
    }

    // ── Helpers ──────────────────────────────────────────────

    private fun formatSeconds(secs: Int): String {
        val h = secs / 3600
        val m = (secs % 3600) / 60
        return if (h > 0) "${h}h ${m}m" else "${m}m"
    }

    /**
     * Simplified TSS estimation:
     * - If power available: TSS ≈ (secs × NP × IF) / (FTP × 3600) × 100
     * - If HR only: use suffer score or rough estimate
     * - Fallback: duration-based estimate
     */
    fun estimateTss(dto: StravaActivityDto): Int {
        // Use suffer score if available (Strava's own intensity metric)
        if (dto.suffer_score != null && dto.suffer_score > 0) {
            return dto.suffer_score
        }

        // Power-based TSS (assume FTP = 250W as default)
        if (dto.weighted_average_watts != null && dto.weighted_average_watts > 0) {
            val ftp = 250.0
            val np = dto.weighted_average_watts.toDouble()
            val IF = np / ftp
            val tss = (dto.moving_time * np * IF) / (ftp * 3600) * 100
            return tss.roundToInt()
        }

        // HR-based estimate (rough)
        if (dto.average_heartrate != null) {
            val hrRatio = (dto.average_heartrate - 60) / (185 - 60)
            val hours = dto.moving_time / 3600.0
            return (hrRatio * hours * 100).roundToInt()
        }

        // Duration fallback: ~50 TSS/hour moderate effort
        return ((dto.moving_time / 3600.0) * 50).roundToInt()
    }
}
