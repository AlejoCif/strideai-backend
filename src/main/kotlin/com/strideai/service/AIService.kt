package com.strideai.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.strideai.dto.*
import com.strideai.model.AiAnalysis
import com.strideai.model.TrainingPlan
import com.strideai.repository.AiAnalysisRepository
import com.strideai.repository.TrainingPlanRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import org.springframework.web.reactive.function.client.bodyToMono
import java.security.MessageDigest
import java.time.DayOfWeek
import java.time.LocalDate

private const val LOW_CREDITS_MSG =
    "⚠️ El análisis IA no está disponible temporalmente por límite de créditos. Intenta más tarde."

@Service
class AIService(
    private val webClient: WebClient,
    private val stravaService: StravaService,
    private val trainingPlanRepository: TrainingPlanRepository,
    private val aiAnalysisRepository: AiAnalysisRepository,
    private val objectMapper: ObjectMapper
) {
    @Value("\${app.anthropic.api-key}") private lateinit var apiKey: String
    @Value("\${app.anthropic.api-url}") private lateinit var apiUrl: String
    @Value("\${app.anthropic.model}") private lateinit var model: String

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

        val reply = callAnthropic(systemPrompt, messages)
        return ChatResponse(reply = reply)
    }

    // ── Training plan ─────────────────────────────────────────

    fun generatePlan(request: GeneratePlanRequest): String {
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

        val planJson = callAnthropic(systemPrompt, listOf(ChatMessage(role = "user", content = userMessage)))
        savePlan(planJson)
        return planJson
    }

    fun getLatestPlan(): TrainingPlanResponse? {
        val plan = trainingPlanRepository.findFirstByUserIdOrderByCreatedAtDesc(1L) ?: return null
        return TrainingPlanResponse(
            id = plan.id,
            planJson = plan.planJson,
            weekTss = plan.weekTss,
            focus = plan.focus,
            weekStartDate = plan.weekStartDate,
            createdAt = plan.createdAt.toString()
        )
    }

    // ── Performance analysis (with cache) ────────────────────

    fun analyzePerformance(): String {
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

        val userId = 1L
        val hash = buildActivitiesHash(activities.take(10))

        // Return cached analysis if activities haven't changed
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
            callAnthropic(systemPrompt, listOf(ChatMessage(role = "user", content = userMessage)))
        } catch (e: WebClientResponseException) {
            if (e.responseBodyAsString.contains("credit balance is too low", ignoreCase = true)) {
                return LOW_CREDITS_MSG
            }
            throw e
        } catch (e: Exception) {
            if (e.message?.contains("credit balance is too low", ignoreCase = true) == true) {
                return LOW_CREDITS_MSG
            }
            throw e
        }

        aiAnalysisRepository.save(
            AiAnalysis(userId = userId, content = analysis, activitiesHash = hash)
        )

        return analysis
    }

    // ── Private helpers ───────────────────────────────────────

    private fun buildActivitiesHash(activities: List<ActivitySummary>): String {
        val data = activities.joinToString("|") {
            "${it.stravaId}:${it.date}:${it.distanceKm}:${it.tss}"
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(data.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    private fun savePlan(planJson: String) {
        try {
            val tree = objectMapper.readTree(planJson)
            val weekTss = tree.get("weekTSS")?.intValue()
            val focus = tree.get("focus")?.textValue()
            val weekStart = LocalDate.now().with(DayOfWeek.MONDAY).toString()

            trainingPlanRepository.save(
                TrainingPlan(
                    userId = 1L,
                    weekStartDate = weekStart,
                    planJson = planJson,
                    weekTss = weekTss,
                    focus = focus
                )
            )
        } catch (e: Exception) {
            println("Warning: could not save training plan: ${e.message}")
        }
    }

    private fun callAnthropic(systemPrompt: String, messages: List<ChatMessage>): String {
        val safeMessages = messages
            .filter { it.content.isNotBlank() }
            .map {
                ChatMessage(
                    role = if (it.role == "assistant") "assistant" else "user",
                    content = it.content.trim()
                )
            }

        if (safeMessages.isEmpty()) {
            throw RuntimeException("Cannot call Anthropic with empty messages")
        }

        val requestBody = AnthropicRequest(
            model = model,
            max_tokens = 1500,
            system = systemPrompt.trim(),
            messages = safeMessages
        )

        val response = try {
            webClient.post()
                .uri(apiUrl)
                .header("x-api-key", apiKey.trim())
                .header("anthropic-version", "2023-06-01")
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(AnthropicResponse::class.java)
                .block()
        } catch (e: WebClientResponseException) {
            println("Anthropic error status=${e.statusCode} body=${e.responseBodyAsString}")
            throw e
        } ?: throw RuntimeException("No response from Anthropic")

        return response.content.firstOrNull()?.text
            ?: throw RuntimeException("Empty response from Anthropic")
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
