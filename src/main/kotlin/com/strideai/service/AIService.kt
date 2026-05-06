package com.strideai.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.strideai.dto.*
import com.fasterxml.jackson.module.kotlin.readValue
import com.strideai.model.AiAnalysis
import com.strideai.model.TrainingPlan
import com.strideai.repository.AiAnalysisRepository
import com.strideai.repository.TrainingPlanRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.security.MessageDigest
import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.math.roundToInt

@Service
class AIService(
    private val anthropicProvider: AnthropicAIProvider,
    private val openAIProvider: OpenAIProvider,
    private val stravaService: StravaService,
    private val trainingPlanRepository: TrainingPlanRepository,
    private val aiAnalysisRepository: AiAnalysisRepository,
    private val objectMapper: ObjectMapper
) {
    @Value("\${app.ai.provider}") private lateinit var aiProviderName: String

    private val logger = LoggerFactory.getLogger(AIService::class.java)

    // ── Chat ─────────────────────────────────────────────────

    fun chat(request: ChatRequest): ChatResponse {
        val recentActivities = try {
            stravaService.getRecentActivities(perPage = 10)
                .map { stravaService.toActivitySummary(it) }
        } catch (e: Exception) {
            emptyList()
        }

        val systemPrompt = buildCoachSystemPrompt(recentActivities)
        val messages = request.conversationHistory.toMutableList()
        messages.add(ChatMessage(role = "user", content = request.message))

        val reply = try {
            callAI(systemPrompt, messages)
        } catch (e: RuntimeException) {
            if (e.message == "AI_UNAVAILABLE") {
                return ChatResponse("⚠️ El entrenador IA no está disponible temporalmente. Intenta más tarde.")
            }
            throw e
        }
        return ChatResponse(reply = reply)
    }

    // ── Training plan ─────────────────────────────────────────

    fun generatePlan(request: GeneratePlanRequest, userId: Long): TrainingPlanData {
        val recentActivities = try {
            stravaService.getRecentActivities(perPage = 20)
                .map { stravaService.toActivitySummary(it) }
        } catch (e: Exception) {
            emptyList()
        }

        val weeklyTss = recentActivities.take(7).sumOf { it.tss ?: 0 }
        val ctlEstimate = recentActivities.take(42).sumOf { it.tss ?: 0 } / 42.0
        val atlEstimate = recentActivities.take(7).sumOf { it.tss ?: 0 } / 7.0
        val tsb = ctlEstimate - atlEstimate

        val systemPrompt = """
            Eres un entrenador deportivo de élite.
            Responde ÚNICAMENTE con JSON válido, sin texto adicional, sin markdown, sin backticks.
        """.trimIndent()

        val userMessage = """
            Genera un plan semanal de entrenamiento personalizado.

            Datos del atleta:
            - CTL estimado: ${ctlEstimate.toInt()} pts
            - ATL estimado: ${atlEstimate.toInt()} pts
            - TSB estimado: ${tsb.toInt()} pts
            - TSS semana actual: $weeklyTss pts
            - Actividades recientes: ${recentActivities.take(5).joinToString { "${it.type} ${it.distanceKm}km" }}
            ${request.goal?.let { "- Objetivo: $it" } ?: ""}
            ${request.weeksToEvent?.let { "- Semanas para el evento: $it" } ?: ""}

            Devuelve exactamente este JSON:
            {
              "plan": [
                {"day":"Lun","type":"rest|easy|interval|threshold|long","label":"nombre sesión","duration":minutos_o_null,"zone":"Z1|Z2|Z3|Z4|Z5|null","note":"consejo corto"}
              ],
              "weekTSS": numero_entero,
              "focus": "frase objetivo de la semana en español",
              "recommendations": ["tip1","tip2","tip3"]
            }

            Importante: incluye exactamente 7 objetos en "plan", de Lun a Dom.
        """.trimIndent()

        logger.info("generatePlan: calling AI for userId=$userId provider=$aiProviderName")

        val planJson = try {
            val json = callAI(systemPrompt, listOf(ChatMessage(role = "user", content = userMessage)))
            logger.info("generatePlan: AI responded OK, length=${json.length}")
            json
        } catch (e: RuntimeException) {
            if (e.message == "AI_UNAVAILABLE") {
                logger.warn("generatePlan: AI unavailable, saving fallback for userId=$userId")
                val fallback = generateFallbackPlan()
                savePlan(objectMapper.writeValueAsString(fallback), userId)
                return fallback
            }
            throw e
        }

        savePlan(planJson, userId)

        return try {
            objectMapper.readValue(planJson, TrainingPlanData::class.java)
        } catch (e: Exception) {
            logger.warn("generatePlan: could not parse AI JSON (${e.message}), saving fallback")
            val fallback = generateFallbackPlan()
            savePlan(objectMapper.writeValueAsString(fallback), userId)
            fallback
        }
    }

    fun getRecentPlans(userId: Long, limit: Int = 5): List<TrainingPlanSummary> {
        return trainingPlanRepository.findByUserIdOrderByCreatedAtDesc(userId)
            .take(limit)
            .mapNotNull { record ->
                try {
                    val data = objectMapper.readValue(record.planJson, TrainingPlanData::class.java)
                    TrainingPlanSummary(
                        id = record.id,
                        title = record.focus ?: data.focus,
                        weekStartDate = record.weekStartDate,
                        weekTSS = record.weekTss ?: data.weekTSS,
                        createdAt = record.createdAt.toString(),
                        plan = data.plan,
                        focus = data.focus,
                        recommendations = data.recommendations
                    )
                } catch (e: Exception) {
                    logger.warn("getRecentPlans: skipping plan id=${record.id}: ${e.message}")
                    null
                }
            }
    }

    fun getLatestPlan(userId: Long): TrainingPlanData? {
        logger.info("getLatestPlan called with userId=$userId")
        val plan = trainingPlanRepository.findFirstByUserIdOrderByCreatedAtDesc(userId)
            ?: run { logger.info("getLatestPlan: no plan found for userId=$userId"); return null }
        logger.info("getLatestPlan: found plan id=${plan.id} focus='${plan.focus}'")
        return try {
            objectMapper.readValue(plan.planJson, TrainingPlanData::class.java)
        } catch (e: Exception) {
            logger.warn("getLatestPlan: could not parse planJson for id=${plan.id}: ${e.message}")
            null
        }
    }

    // ── Performance analysis (cache + provider + local fallback) ─

    fun analyzePerformance(userId: Long): String {
        val activities = try {
            stravaService.getRecentActivities(perPage = 20)
                .map { stravaService.toActivitySummary(it) }
        } catch (e: Exception) {
            return "No se pudieron cargar las actividades de Strava."
        }

        if (activities.isEmpty()) {
            return "No hay actividades suficientes para generar un análisis. " +
                "Sincroniza tus actividades de Strava primero."
        }

        val hash = buildActivitiesHash(activities.take(10))

        val cached = aiAnalysisRepository.findFirstByUserIdOrderByCreatedAtDesc(userId)
        if (cached != null && cached.activitiesHash == hash) {
            return cached.content
        }

        val activityLines = activities.take(10).joinToString("\n") {
            "- ${it.date}: ${it.type}, ${it.distanceKm} km, ${it.movingTimeFormatted}, " +
                "TSS: ${it.tss ?: 0}, FC media: ${it.avgHeartrate?.toInt()?.toString() ?: "N/A"} bpm, " +
                "Potencia media: ${it.avgWatts?.toInt()?.toString() ?: "N/A"} W"
        }

        val systemPrompt = """
            Eres un entrenador deportivo de élite especializado en análisis de rendimiento.
            Responde en español, de forma clara y accionable.
            Máximo 4 párrafos cortos.
            Usa emojis con moderación.
            No uses markdown complejo ni tablas.
        """.trimIndent()

        val userMessage = """
            Analiza el rendimiento de este atleta basándote en sus últimas actividades:

            $activityLines

            Debes identificar:
            1. Tendencias recientes.
            2. Puntos fuertes.
            3. Áreas de mejora.
            4. Dos o tres recomendaciones concretas para la próxima semana.
        """.trimIndent()

        val analysis = try {
            callAI(systemPrompt, listOf(ChatMessage(role = "user", content = userMessage)))
        } catch (e: RuntimeException) {
            if (e.message == "AI_UNAVAILABLE") {
                return generateFallbackAnalysis(activities)
            }
            throw e
        }

        aiAnalysisRepository.save(
            AiAnalysis(userId = userId, content = analysis, activitiesHash = hash)
        )

        return analysis
    }

    // ── Provider selection ────────────────────────────────────

    private fun callAI(
        systemPrompt: String,
        messages: List<ChatMessage>,
        maxTokens: Int = 1500
    ): String {
        val provider: AIProvider = when (aiProviderName.lowercase().trim()) {
            "openai" -> openAIProvider
            else -> anthropicProvider
        }
        return provider.generate(systemPrompt, messages, maxTokens)
    }

    // ── Local fallbacks ───────────────────────────────────────

    private fun generateFallbackAnalysis(activities: List<ActivitySummary>): String {
        if (activities.isEmpty()) return "No hay suficientes actividades para generar análisis."

        val week = activities.take(7)
        val totalKm = week.sumOf { it.distanceKm }
        val totalTss = week.sumOf { it.tss ?: 0 }
        val avgHr = week.mapNotNull { it.avgHeartrate }
            .let { hrs -> if (hrs.isEmpty()) null else hrs.average().roundToInt() }

        val trend = when {
            totalTss > 400 -> "alto"
            totalTss > 200 -> "moderado"
            else -> "bajo"
        }

        val dominantType = week.groupingBy { it.type }.eachCount()
            .maxByOrNull { it.value }?.key ?: "variado"

        val strengths = buildList {
            if (totalKm > 50) add("volumen semanal sólido (${totalKm.roundToInt()} km)")
            if (totalTss > 300) add("carga de entrenamiento consistente ($totalTss TSS)")
            if (avgHr != null && avgHr < 150) add("frecuencia cardíaca media controlada ($avgHr bpm)")
            if (week.size >= 4) add("frecuencia de entrenamiento regular (${week.size} sesiones)")
        }.ifEmpty { listOf("constancia en el entrenamiento") }

        val improvements = buildList {
            if (totalKm < 30) add("aumentar el volumen semanal gradualmente")
            if (totalTss < 150) add("incrementar la carga de entrenamiento")
            if (avgHr != null && avgHr > 165) add("incluir más trabajo en zona aeróbica baja")
            if (week.size < 3) add("aumentar la frecuencia semanal de sesiones")
        }.ifEmpty { listOf("mantener la consistencia y variar los estímulos") }

        val recommendations = buildList {
            add("Realiza al menos una sesión larga de ${dominantType.lowercase()} a intensidad baja (Z2) esta semana.")
            if (totalTss > 350) add("Incluye un día de recuperación activa o descanso completo.")
            else add("Puedes aumentar el volumen un 10% respecto a esta semana.")
            add("Revisa tu hidratación y sueño para optimizar la recuperación entre sesiones.")
        }

        return buildString {
            appendLine("📊 Resumen semanal")
            appendLine("Distancia total: ${totalKm.roundToInt()} km · TSS acumulado: $totalTss · " +
                "Sesiones: ${week.size} · Carga: $trend")
            avgHr?.let { appendLine("Frecuencia cardíaca media: $it bpm") }
            appendLine()

            appendLine("💪 Puntos fuertes")
            strengths.forEach { appendLine("• ${it.replaceFirstChar { c -> c.uppercase() }}") }
            appendLine()

            appendLine("📈 Áreas de mejora")
            improvements.forEach { appendLine("• ${it.replaceFirstChar { c -> c.uppercase() }}") }
            appendLine()

            appendLine("✅ Recomendaciones para la próxima semana")
            recommendations.forEach { appendLine("• $it") }
        }.trim()
    }

    private fun generateFallbackPlan(): TrainingPlanData = TrainingPlanData(
        plan = listOf(
            TrainingDay("Lun", "easy",      "Rodaje suave", 45,   "Z2", "Mantén conversación fácil"),
            TrainingDay("Mar", "rest",      "Descanso",     null, null, "Recuperación completa"),
            TrainingDay("Mié", "easy",      "Trote fácil",  40,   "Z2", "Ritmo cómodo"),
            TrainingDay("Jue", "threshold", "Series",       50,   "Z4", "4×8 min al umbral"),
            TrainingDay("Vie", "rest",      "Descanso",     null, null, "Recuperación activa o descanso"),
            TrainingDay("Sáb", "long",      "Fondo",        90,   "Z2", "Sin prisa, hidrata bien"),
            TrainingDay("Dom", "rest",      "Descanso",     null, null, "Descansa y recupera")
        ),
        weekTSS = 180,
        focus = "Semana de mantenimiento — el asistente IA no está disponible temporalmente",
        recommendations = listOf(
            "Mantén una intensidad moderada esta semana.",
            "Descansa bien y cuida la alimentación.",
            "Próximamente podrás obtener un plan personalizado con IA."
        )
    )

    // ── Helpers ───────────────────────────────────────────────

    private fun buildActivitiesHash(activities: List<ActivitySummary>): String {
        val data = activities.joinToString("|") {
            "${it.stravaId}:${it.date}:${it.distanceKm}:${it.tss}"
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(data.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    private fun savePlan(planJson: String, userId: Long) {
        try {
            val tree = objectMapper.readTree(planJson)
            val weekTss = tree.get("weekTSS")?.intValue()
            val focus = tree.get("focus")?.textValue()
            val weekStart = LocalDate.now().with(DayOfWeek.MONDAY).toString()

            logger.info("savePlan: persisting for userId=$userId focus='$focus' weekTss=$weekTss")
            val saved = trainingPlanRepository.save(
                TrainingPlan(
                    userId = userId,
                    weekStartDate = weekStart,
                    planJson = planJson,
                    weekTss = weekTss,
                    focus = focus
                )
            )
            logger.info("savePlan: saved with id=${saved.id}")
        } catch (e: Exception) {
            logger.error("savePlan: FAILED for userId=$userId — ${e.message}")
        }
    }

    private fun buildCoachSystemPrompt(activities: List<ActivitySummary>): String {
        val weekTss = activities.take(7).sumOf { it.tss ?: 0 }
        val ctlEstimate = activities.take(42).sumOf { it.tss ?: 0 } / 42.0

        return """
            Eres un entrenador deportivo de élite personalizado. Conoces los datos reales del atleta.

            DATOS DEL ATLETA:
            - CTL estimado: ${ctlEstimate.toInt()} pts
            - TSS esta semana: $weekTss pts
            - Últimas actividades:
            ${activities.take(6).joinToString("\n") {
            "  • ${it.date} ${it.type}: ${it.distanceKm}km, ${it.movingTimeFormatted}, TSS ${it.tss ?: 0}"
        }}

            INSTRUCCIONES:
            - Responde en español, tono motivador pero directo.
            - Usa los datos reales para personalizar cada respuesta.
            - Sé conciso: máximo 3 párrafos.
            - Si preguntan sobre nutrición, hidratación o lesiones, recomienda consultar un profesional.
        """.trimIndent()
    }
}
