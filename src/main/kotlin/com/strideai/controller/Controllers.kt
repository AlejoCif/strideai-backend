package com.strideai.controller

import com.strideai.dto.*
import com.strideai.model.ActivityZones
import com.strideai.model.AthleteProfile as AthleteProfileEntity
import com.strideai.repository.ActivityRepository
import com.strideai.repository.ActivityZonesRepository
import com.strideai.repository.AiUsageLogRepository
import com.strideai.repository.AthleteProfileRepository
import com.strideai.repository.UserRepository
import com.strideai.service.AIService
import com.strideai.service.AiUsageService
import com.strideai.service.StravaService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.*
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// ── Health ────────────────────────────────────────────────

@RestController
class HealthController {
    @GetMapping("/api/health")
    fun health() = mapOf(
        "status" to "ok",
        "service" to "StrideAI Backend",
        "version" to "1.0.0"
    )
}

// ── Athlete ───────────────────────────────────────────────

@RestController
@RequestMapping("/api/athlete")
class AthleteController(
    private val stravaService: StravaService,
    private val athleteProfileRepository: AthleteProfileRepository
) {

    @GetMapping
    fun getAthlete(): ResponseEntity<AthleteProfile> {
        val userId = SecurityContextHolder.getContext().authentication.principal as Long
        val athlete = stravaService.getAthlete(userId)
        return ResponseEntity.ok(
            AthleteProfile(
                stravaId = athlete.id,
                name = "${athlete.firstname} ${athlete.lastname}",
                profileImageUrl = athlete.profile,
                city = athlete.city,
                country = athlete.country
            )
        )
    }

    @GetMapping("/profile")
    fun getProfile(): ResponseEntity<AthleteProfileDto> {
        val userId = SecurityContextHolder.getContext().authentication.principal as Long
        val profile = athleteProfileRepository.findByUserId(userId)
            ?: return ResponseEntity.ok(AthleteProfileDto())
        return ResponseEntity.ok(
            AthleteProfileDto(
                weightKg = profile.weightKg?.toDouble(),
                mainSport = profile.mainSport,
                goalEvent = profile.goalEvent,
                goalDate = profile.goalDate?.toString(),
                equipment = profile.equipment,
                notes = profile.notes
            )
        )
    }

    @PutMapping("/profile")
    fun updateProfile(@RequestBody dto: AthleteProfileDto): ResponseEntity<AthleteProfileDto> {
        val userId = SecurityContextHolder.getContext().authentication.principal as Long
        val existing = athleteProfileRepository.findByUserId(userId)
        val goalDate = dto.goalDate?.let { java.time.LocalDate.parse(it) }
        val entity = existing?.copy(
            weightKg = dto.weightKg?.let { java.math.BigDecimal.valueOf(it) } ?: existing.weightKg,
            mainSport = dto.mainSport ?: existing.mainSport,
            goalEvent = dto.goalEvent ?: existing.goalEvent,
            goalDate = goalDate ?: existing.goalDate,
            equipment = dto.equipment ?: existing.equipment,
            notes = dto.notes ?: existing.notes,
            updatedAt = java.time.Instant.now()
        ) ?: AthleteProfileEntity(
            userId = userId,
            weightKg = dto.weightKg?.let { java.math.BigDecimal.valueOf(it) },
            mainSport = dto.mainSport,
            goalEvent = dto.goalEvent,
            goalDate = goalDate,
            equipment = dto.equipment,
            notes = dto.notes
        )
        val saved = athleteProfileRepository.save(entity)
        return ResponseEntity.ok(
            AthleteProfileDto(
                weightKg = saved.weightKg?.toDouble(),
                mainSport = saved.mainSport,
                goalEvent = saved.goalEvent,
                goalDate = saved.goalDate?.toString(),
                equipment = saved.equipment,
                notes = saved.notes
            )
        )
    }
}

// ── Activities ────────────────────────────────────────────

@RestController
@RequestMapping("/api/activities")
class ActivitiesController(
    private val stravaService: StravaService,
    private val activityRepository: ActivityRepository,
    private val activityZonesRepository: ActivityZonesRepository,
    private val userRepository: UserRepository
) {
    @org.springframework.beans.factory.annotation.Value("\${app.admin-strava-id}")
    private var adminStravaId: Long = 0

    private val logger = org.slf4j.LoggerFactory.getLogger(ActivitiesController::class.java)

    private fun isAdmin(userId: Long): Boolean =
        userRepository.findById(userId).orElse(null)?.stravaId == adminStravaId

    @GetMapping
    fun getActivities(
        @RequestParam(defaultValue = "20") limit: Int,
        @RequestParam(defaultValue = "1") page: Int
    ): ResponseEntity<List<ActivitySummary>> {
        val userId = SecurityContextHolder.getContext().authentication.principal as Long
        val activities = stravaService.getRecentActivities(userId = userId, perPage = limit, page = page)
            .map { stravaService.toActivitySummary(it) }
        return ResponseEntity.ok(activities)
    }

    @GetMapping("/stats")
    fun getWeeklyStats(): ResponseEntity<Map<String, Any>> {
        val userId = SecurityContextHolder.getContext().authentication.principal as Long
        val activities = stravaService.getRecentActivities(userId = userId, perPage = 100)

        val bogotaToday = LocalDate.now(ZoneId.of("America/Bogota"))
        val weekStart = bogotaToday.with(DayOfWeek.MONDAY)
        val labelFormatter = DateTimeFormatter.ofPattern("dd/MM")

        val actsByMonday = activities.groupBy { act ->
            LocalDate.parse(act.start_date_local.take(10)).with(DayOfWeek.MONDAY)
        }

        val grouped = (0..11).map { i ->
            val monday = weekStart.minusWeeks(i.toLong())
            val acts = actsByMonday[monday] ?: emptyList()
            WeeklyStats(
                weekLabel = "Sem ${monday.format(labelFormatter)}",
                totalDistanceKm = acts.sumOf { it.distance } / 1000,
                totalTimeHours = acts.sumOf { it.moving_time } / 3600.0,
                totalElevation = acts.sumOf { it.total_elevation_gain },
                activityCount = acts.size,
                estimatedTss = acts.sumOf { stravaService.estimateTss(it) }
            )
        }.reversed()

        val currentWeekActs = activities.filter { act ->
            LocalDate.parse(act.start_date_local.take(10)) >= weekStart
        }

        val recentActs = activities.take(42)
        val ctl = recentActs.sumOf { stravaService.estimateTss(it) } / 42.0
        val atl = recentActs.take(7).sumOf { stravaService.estimateTss(it) } / 7.0

        return ResponseEntity.ok(mapOf(
            "weekly" to grouped,
            "fitness" to FitnessMetrics(
                ctl = Math.round(ctl * 10) / 10.0,
                atl = Math.round(atl * 10) / 10.0,
                tsb = Math.round((ctl - atl) * 10) / 10.0,
                weeklyTss = currentWeekActs.sumOf { stravaService.estimateTss(it) }
            )
        ))
    }

    @PostMapping("/resync")
    fun resyncActivities(): ResponseEntity<Map<String, Int>> {
        val userId = SecurityContextHolder.getContext().authentication.principal as Long
        val stravaActivities = stravaService.getRecentActivities(userId = userId, perPage = 100)

        var inserted = 0
        var updated = 0

        for (dto in stravaActivities) {
            val existing = activityRepository.findByStravaIdAndUserId(dto.id, userId)
            val activity = stravaService.toActivity(dto, userId).let { new ->
                if (existing != null) new.copy(createdAt = existing.createdAt)
                else new
            }
            activityRepository.save(activity)
            if (existing != null) updated++ else inserted++
        }

        return ResponseEntity.ok(mapOf("synced" to inserted, "updated" to updated))
    }

    @PostMapping("/sync")
    fun syncActivities(): ResponseEntity<SyncResponse> {
        val userId = SecurityContextHolder.getContext().authentication.principal as Long
        val latest = activityRepository.findRecentByUserId(userId, 1).firstOrNull()
        val stravaActivities = if (latest != null) {
            stravaService.getActivitiesSince(latest.startDate.epochSecond, userId)
        } else {
            stravaService.getRecentActivities(userId = userId, perPage = 100)
        }
        val activities = stravaActivities.map { stravaService.toActivity(it, userId) }
        activityRepository.saveAll(activities)

        // Sync heart-rate zones for the 5 most recent activities
        val zonesUpdated = activityRepository
            .findRecentByUserId(userId, 5)
            .count { recent ->
                val json = stravaService.getActivityZones(recent.stravaId, userId)
                if (json != null) {
                    activityZonesRepository.save(
                        ActivityZones(
                            stravaActivityId = recent.stravaId,
                            userId = userId,
                            zonesJson = json
                        )
                    )
                    true
                } else false
            }

        return ResponseEntity.ok(
            SyncResponse(synced = stravaActivities.size, success = true, zonesUpdated = zonesUpdated)
        )
    }

    @PostMapping("/backfill")
    fun backfillHistory(): ResponseEntity<Map<String, Any>> {
        val userId = SecurityContextHolder.getContext().authentication.principal as Long

        if (!isAdmin(userId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(mapOf("error" to "Admin only"))
        }

        var inserted = 0
        var skipped = 0
        var page = 1
        var rateLimited = false
        var errorPage: Int? = null

        logger.info("Backfill started for userId=$userId")

        loop@ while (true) {
            val batch: List<com.strideai.dto.StravaActivityDto> = try {
                stravaService.fetchPageForBackfill(userId = userId, page = page, perPage = 200)
            } catch (e: RuntimeException) {
                if (e.message == "STRAVA_RATE_LIMITED") {
                    logger.warn("Backfill: Strava rate limit hit on page $page — stopping early")
                    rateLimited = true
                    break@loop
                }
                logger.error("Backfill: error on page $page — ${e.message}")
                errorPage = page
                break@loop
            }

            if (batch.isEmpty()) {
                logger.info("Backfill: page $page returned empty — done")
                break@loop
            }

            for (dto in batch) {
                if (activityRepository.findByStravaIdAndUserId(dto.id, userId) != null) {
                    skipped++
                } else {
                    activityRepository.save(stravaService.toActivity(dto, userId))
                    inserted++
                }
            }

            logger.info("Backfill page $page: +$inserted inserted, $skipped skipped so far")
            Thread.sleep(300)
            page++
        }

        val totalInDb   = activityRepository.countByUserId(userId)
        val minDate     = activityRepository.findMinStartDate(userId)?.toString() ?: "—"
        val maxDate     = activityRepository.findMaxStartDate(userId)?.toString() ?: "—"

        val result = mutableMapOf<String, Any>(
            "inserted"    to inserted,
            "skipped"     to skipped,
            "totalInDb"   to totalInDb,
            "dateRangeMin" to minDate,
            "dateRangeMax" to maxDate,
            "pagesProcessed" to page - 1
        )
        if (rateLimited) result["warning"] = "Stopped early due to Strava rate limit"
        if (errorPage != null) result["errorPage"] = errorPage

        logger.info("Backfill complete: $result")
        return ResponseEntity.ok(result)
    }
}

// ── AI ────────────────────────────────────────────────────

@RestController
@RequestMapping("/api/ai")
class AIController(
    private val aiService: AIService,
    private val aiUsageService: AiUsageService
) {

    @PostMapping("/chat")
    fun chat(@RequestBody request: ChatRequest): ResponseEntity<Any> {
        val userId = currentUserId()
        if (!aiUsageService.checkAndIncrementChat(userId)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(
                mapOf(
                    "error" to "quota_exceeded",
                    "message" to "🌙 Has alcanzado el límite de 10 mensajes diarios. Tu cuota se renueva a medianoche.",
                    "remaining" to 0
                )
            )
        }
        return ResponseEntity.ok(aiService.chat(request, userId))
    }

    @GetMapping("/chat/history")
    fun getChatHistory(): ResponseEntity<List<Map<String, String>>> =
        ResponseEntity.ok(aiService.getChatHistory(currentUserId()))

    @DeleteMapping("/chat/history")
    fun clearChatHistory(): ResponseEntity<Map<String, String>> {
        aiService.clearChatHistory(currentUserId())
        return ResponseEntity.ok(mapOf("message" to "Historial eliminado"))
    }

    @GetMapping("/usage")
    fun getUsage(): ResponseEntity<Map<String, Any>> =
        ResponseEntity.ok(aiUsageService.getUsage(currentUserId()))

    @PostMapping("/plan")
    fun generatePlan(@RequestBody request: GeneratePlanRequest): ResponseEntity<Any> {
        return try {
            ResponseEntity.ok(aiService.generatePlan(request, currentUserId()))
        } catch (e: Exception) {
            ResponseEntity.internalServerError()
                .body(mapOf("error" to (e.message ?: "Error generating plan"), "success" to false))
        }
    }

    @GetMapping("/plan/history")
    fun getPlanHistory(
        @RequestParam(defaultValue = "5") limit: Int
    ): ResponseEntity<List<Any>> {
        val plans = aiService.getRecentPlans(currentUserId(), limit.coerceIn(1, 20))
        return ResponseEntity.ok(plans)
    }

    @GetMapping("/plan/latest")
    fun getLatestPlan(): ResponseEntity<Any> {
        val plan = aiService.getLatestPlan(currentUserId())
            ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(plan)
    }

    @GetMapping("/analysis")
    fun analyzePerformance(): ResponseEntity<Map<String, String>> {
        val analysis = aiService.analyzePerformance(currentUserId())
        return ResponseEntity.ok(mapOf("analysis" to analysis))
    }

    private fun currentUserId(): Long =
        SecurityContextHolder.getContext().authentication.principal as Long
}

// ── Admin ─────────────────────────────────────────────────

// Precios por millón de tokens (USD) — actualizar aquí si cambian las tarifas
private const val ANTHROPIC_INPUT_COST_PER_M  = 3.00
private const val ANTHROPIC_OUTPUT_COST_PER_M = 15.00
private const val OPENAI_INPUT_COST_PER_M     = 0.15
private const val OPENAI_OUTPUT_COST_PER_M    = 0.60

@RestController
@RequestMapping("/api/admin")
class AdminController(
    private val aiUsageLogRepository: AiUsageLogRepository,
    private val userRepository: UserRepository
) {
    @org.springframework.beans.factory.annotation.Value("\${app.admin-strava-id}")
    private var adminStravaId: Long = 0

    private fun isAdmin(userId: Long): Boolean =
        userRepository.findById(userId).orElse(null)?.stravaId == adminStravaId

    @GetMapping("/usage-total")
    fun getUsageTotal(): ResponseEntity<Any> {
        val userId = SecurityContextHolder.getContext().authentication.principal as Long
        if (!isAdmin(userId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(mapOf("error" to "Admin only"))
        }

        val anthropicIn  = aiUsageLogRepository.sumInputTokens("anthropic")
        val anthropicOut = aiUsageLogRepository.sumOutputTokens("anthropic")
        val openaiIn     = aiUsageLogRepository.sumInputTokens("openai")
        val openaiOut    = aiUsageLogRepository.sumOutputTokens("openai")
        val totalMessages = aiUsageLogRepository.count()

        val anthropicCost = (anthropicIn  / 1_000_000.0) * ANTHROPIC_INPUT_COST_PER_M +
                            (anthropicOut / 1_000_000.0) * ANTHROPIC_OUTPUT_COST_PER_M
        val openaiCost    = (openaiIn     / 1_000_000.0) * OPENAI_INPUT_COST_PER_M +
                            (openaiOut    / 1_000_000.0) * OPENAI_OUTPUT_COST_PER_M
        val totalUSD      = anthropicCost + openaiCost

        return ResponseEntity.ok(mapOf(
            "totalUSD"      to Math.round(totalUSD * 10000) / 10000.0,
            "totalMessages" to totalMessages,
            "byProvider" to mapOf(
                "anthropic" to mapOf(
                    "costUSD"      to Math.round(anthropicCost * 10000) / 10000.0,
                    "inputTokens"  to anthropicIn,
                    "outputTokens" to anthropicOut
                ),
                "openai" to mapOf(
                    "costUSD"      to Math.round(openaiCost * 10000) / 10000.0,
                    "inputTokens"  to openaiIn,
                    "outputTokens" to openaiOut
                )
            )
        ))
    }
}
