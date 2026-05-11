package com.strideai.service

import com.strideai.dto.*
import com.strideai.model.Activity
import com.strideai.repository.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import org.springframework.web.reactive.function.client.bodyToMono
import java.time.Instant
import java.time.ZonedDateTime
import kotlin.math.roundToInt

@Service
class StravaService(
    private val webClient: WebClient,
    private val userRepository: UserRepository
) {
    @Value("\${app.strava.client-id}") private lateinit var clientId: String
    @Value("\${app.strava.client-secret}") private lateinit var clientSecret: String
    @Value("\${app.strava.api-base-url}") private lateinit var apiBaseUrl: String
    @Value("\${app.strava.token-url}") private lateinit var tokenUrl: String

    private val logger = LoggerFactory.getLogger(StravaService::class.java)

    // In-memory cache — survives multiple requests, cleared on restart.
    // DB is the source of truth after a server restart.
    @Volatile private var cachedAccessToken: String? = null
    @Volatile private var cachedTokenExpiresAt: Long = 0

    // Called from StravaAuthController after OAuth to prime the in-memory cache
    // without an extra DB read (tokens already persisted by upsertUser).
    fun updateTokenCache(accessToken: String, refreshToken: String, expiresAt: Long) {
        cachedAccessToken = accessToken
        cachedTokenExpiresAt = expiresAt
    }

    // ── Token management ──────────────────────────────────────

    /**
     * Returns a valid access token for [userId].
     * Reads from DB, refreshes with Strava if expiring, persists new tokens.
     * Throws RuntimeException("STRAVA_AUTH_REVOKED") if refresh token is invalid.
     */
    fun getValidToken(userId: Long): String {
        val now = Instant.now().epochSecond
        val user = userRepository.findById(userId).orElse(null)
            ?: throw RuntimeException("User not found: $userId")

        if (user.tokenExpiresAt > now + 300) {
            cachedAccessToken = user.accessToken
            cachedTokenExpiresAt = user.tokenExpiresAt
            return user.accessToken
        }

        logger.info("Strava token expired for userId=$userId, refreshing...")
        return refreshAndPersist(user.refreshToken, userId)
    }

    /**
     * No-arg overload: uses in-memory cache first, then first DB user.
     * Used by call sites that don't have a userId (e.g. AIService analysis).
     */
    fun getValidToken(): String {
        val now = Instant.now().epochSecond
        if (cachedAccessToken != null && cachedTokenExpiresAt > now + 300) {
            return cachedAccessToken!!
        }
        val user = userRepository.findAll().firstOrNull()
            ?: throw RuntimeException("No authenticated Strava user found")
        return getValidToken(user.id)
    }

    private fun refreshAndPersist(refreshToken: String, userId: Long): String {
        val response = try {
            webClient.post()
                .uri(tokenUrl)
                .bodyValue(mapOf(
                    "client_id" to clientId,
                    "client_secret" to clientSecret,
                    "refresh_token" to refreshToken,
                    "grant_type" to "refresh_token"
                ))
                .retrieve()
                .bodyToMono<StravaTokenResponse>()
                .block() ?: throw RuntimeException("Empty response from Strava token endpoint")
        } catch (e: WebClientResponseException) {
            if (e.statusCode.value() == 401) {
                logger.error("Strava refresh token revoked for userId=$userId")
                throw RuntimeException("STRAVA_AUTH_REVOKED")
            }
            throw e
        }

        userRepository.findById(userId).orElse(null)?.let { user ->
            userRepository.save(user.copy(
                accessToken = response.access_token,
                refreshToken = response.refresh_token,
                tokenExpiresAt = response.expires_at,
                updatedAt = Instant.now()
            ))
        }

        cachedAccessToken = response.access_token
        cachedTokenExpiresAt = response.expires_at

        logger.info("Strava token refreshed for userId=$userId, expires: ${Instant.ofEpochSecond(response.expires_at)}")
        return response.access_token
    }

    // ── Strava API calls ──────────────────────────────────────

    fun getAthlete(): StravaAthleteDto {
        return try {
            webClient.get()
                .uri("$apiBaseUrl/athlete")
                .header("Authorization", "Bearer ${getValidToken()}")
                .retrieve()
                .bodyToMono<StravaAthleteDto>()
                .block() ?: throw RuntimeException("Failed to fetch athlete")
        } catch (e: WebClientResponseException) {
            handleStravaError(e)
        }
    }

    fun getRecentActivities(perPage: Int = 30, page: Int = 1): List<StravaActivityDto> {
        return try {
            webClient.get()
                .uri("$apiBaseUrl/athlete/activities?per_page=$perPage&page=$page")
                .header("Authorization", "Bearer ${getValidToken()}")
                .retrieve()
                .bodyToMono<List<StravaActivityDto>>()
                .block() ?: emptyList()
        } catch (e: WebClientResponseException) {
            handleStravaError(e)
        }
    }

    fun getActivityZones(activityId: Long): String? {
        return try {
            val token = getValidToken()
            val raw = webClient.get()
                .uri("$apiBaseUrl/activities/$activityId/zones")
                .header("Authorization", "Bearer $token")
                .retrieve()
                .bodyToMono(String::class.java)
                .block()
            logger.info("Fetched zones for activity $activityId")
            raw
        } catch (e: WebClientResponseException) {
            logger.warn("Could not fetch zones for activity $activityId: ${e.statusCode}")
            null
        }
    }

    fun getActivitiesSince(epochSeconds: Long): List<StravaActivityDto> {
        return try {
            webClient.get()
                .uri("$apiBaseUrl/athlete/activities?after=$epochSeconds&per_page=100")
                .header("Authorization", "Bearer ${getValidToken()}")
                .retrieve()
                .bodyToMono<List<StravaActivityDto>>()
                .block() ?: emptyList()
        } catch (e: WebClientResponseException) {
            handleStravaError(e)
        }
    }

    private fun handleStravaError(e: WebClientResponseException): Nothing = when (e.statusCode.value()) {
        429 -> throw RuntimeException("STRAVA_RATE_LIMITED")
        401 -> throw RuntimeException("STRAVA_AUTH_REVOKED")
        else -> throw e
    }

    // ── Conversions ───────────────────────────────────────────

    fun toActivitySummary(dto: StravaActivityDto): ActivitySummary = ActivitySummary(
        stravaId = dto.id,
        name = dto.name,
        type = dto.sport_type,
        date = dto.start_date_local.take(10),
        distanceKm = (dto.distance / 1000 * 10).roundToInt() / 10.0,
        movingTimeFormatted = formatSeconds(dto.moving_time),
        elevationGain = dto.total_elevation_gain,
        avgHeartrate = dto.average_heartrate,
        avgWatts = dto.average_watts,
        avgCadence = dto.average_cadence,
        calories = dto.kilojoules?.let { it * 0.239 } ?: dto.calories,
        tss = estimateTss(dto)
    )

    fun toActivity(dto: StravaActivityDto, userId: Long): Activity {
        logger.info(
            "Activity ${dto.id} (${dto.sport_type}): " +
            "kilojoules=${dto.kilojoules}, calories=${dto.calories}, " +
            "cadence=${dto.average_cadence}, watts=${dto.average_watts}"
        )
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
        averageCadence = dto.average_cadence,
        calories = dto.kilojoules?.let { it * 0.239 } ?: dto.calories,
        startDate = ZonedDateTime.parse(dto.start_date).toInstant(),
        startDateLocal = dto.start_date_local,
        timezone = dto.timezone,
        kudosCount = dto.kudos_count,
        achievementCount = dto.achievement_count,
        prCount = dto.pr_count,
        sufferScore = dto.suffer_score
        )
    }

    // ── Helpers ───────────────────────────────────────────────

    private fun formatSeconds(secs: Int): String {
        val h = secs / 3600
        val m = (secs % 3600) / 60
        return if (h > 0) "${h}h ${m}m" else "${m}m"
    }

    fun estimateTss(dto: StravaActivityDto): Int {
        if (dto.suffer_score != null && dto.suffer_score > 0) return dto.suffer_score

        if (dto.weighted_average_watts != null && dto.weighted_average_watts > 0) {
            val ftp = 250.0
            val np = dto.weighted_average_watts.toDouble()
            val IF = np / ftp
            return ((dto.moving_time * np * IF) / (ftp * 3600) * 100).roundToInt()
        }

        if (dto.average_heartrate != null) {
            val hrRatio = (dto.average_heartrate - 60) / (185 - 60)
            return (hrRatio * (dto.moving_time / 3600.0) * 100).roundToInt()
        }

        return ((dto.moving_time / 3600.0) * 50).roundToInt()
    }
}
