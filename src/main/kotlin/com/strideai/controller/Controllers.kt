package com.strideai.controller

import com.strideai.dto.*
import com.strideai.repository.ActivityRepository
import com.strideai.service.AIService
import com.strideai.service.AiUsageService
import com.strideai.service.StravaService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.*

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
class AthleteController(private val stravaService: StravaService) {

    @GetMapping
    fun getAthlete(): ResponseEntity<AthleteProfile> {
        val athlete = stravaService.getAthlete()
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
}

// ── Activities ────────────────────────────────────────────

@RestController
@RequestMapping("/api/activities")
class ActivitiesController(
    private val stravaService: StravaService,
    private val activityRepository: ActivityRepository
) {

    @GetMapping
    fun getActivities(
        @RequestParam(defaultValue = "20") limit: Int,
        @RequestParam(defaultValue = "1") page: Int
    ): ResponseEntity<List<ActivitySummary>> {
        val activities = stravaService.getRecentActivities(perPage = limit, page = page)
            .map { stravaService.toActivitySummary(it) }
        return ResponseEntity.ok(activities)
    }

    @GetMapping("/stats")
    fun getWeeklyStats(): ResponseEntity<Map<String, Any>> {
        val activities = stravaService.getRecentActivities(perPage = 100)

        val grouped = activities.groupBy { it.start_date_local.take(7) }
            .entries
            .take(8)
            .map { (week, acts) ->
                WeeklyStats(
                    weekLabel = week,
                    totalDistanceKm = acts.sumOf { it.distance } / 1000,
                    totalTimeHours = acts.sumOf { it.moving_time } / 3600.0,
                    totalElevation = acts.sumOf { it.total_elevation_gain },
                    activityCount = acts.size,
                    estimatedTss = acts.sumOf { stravaService.estimateTss(it) }
                )
            }

        val recentActs = activities.take(42)
        val ctl = recentActs.sumOf { stravaService.estimateTss(it) } / 42.0
        val atl = activities.take(7).sumOf { stravaService.estimateTss(it) } / 7.0

        return ResponseEntity.ok(mapOf(
            "weekly" to grouped,
            "fitness" to FitnessMetrics(
                ctl = Math.round(ctl * 10) / 10.0,
                atl = Math.round(atl * 10) / 10.0,
                tsb = Math.round((ctl - atl) * 10) / 10.0,
                weeklyTss = activities.take(7).sumOf { stravaService.estimateTss(it) }
            )
        ))
    }

    @PostMapping("/sync")
    fun syncActivities(): ResponseEntity<SyncResponse> {
        val userId = 1L
        val latest = activityRepository.findRecentByUserId(userId, 1).firstOrNull()
        val stravaActivities = if (latest != null) {
            stravaService.getActivitiesSince(latest.startDate.epochSecond)
        } else {
            stravaService.getRecentActivities(perPage = 100)
        }
        val activities = stravaActivities.map { stravaService.toActivity(it, userId) }
        activityRepository.saveAll(activities)
        return ResponseEntity.ok(SyncResponse(synced = stravaActivities.size, success = true))
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
        return ResponseEntity.ok(aiService.chat(request))
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
