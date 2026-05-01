package com.strideai.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.strideai.dto.*
import com.strideai.model.TrainingPlan
import com.strideai.repository.TrainingPlanRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToMono
import java.time.DayOfWeek
import java.time.LocalDate

@Service
class AIService(
    private val webClient: WebClient,
    private val stravaService: StravaService,
    private val trainingPlanRepository: TrainingPlanRepository,
    private val objectMapper: ObjectMapper
) {
    @Value("\${app.anthropic.api-key}") private lateinit var apiKey: String
    @Value("\${app.anthropic.api-url}") private lateinit var apiUrl: String
    @Value("\${app.anthropic.model}") private lateinit var model: String

    // ── Chat with AI coach ───────────────────────────────────

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

    // ── Generate weekly training plan ────────────────────────

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
            ${if (request.goal != null) "- Objetivo: ${request.goal}" else ""}
            ${if (request.weeksToEvent != null) "- Semanas para el evento: ${request.weeksToEvent}" else ""}

            Devuelve exactamente este JSON:
            {
              "plan": [
                {"day":"Lun","type":"rest|easy|interval|threshold|long","label":"nombre sesión","duration":minutos_o_null,"zone":"Z1|Z2|Z3|Z4|Z5|null","note":"consejo corto"},
                ... (7 días, Lun a Dom)
              ],
              "weekTSS": numero_entero,
              "focus": "frase objetivo de la semana en español",
              "recommendations": ["tip1","tip2","tip3"]
            }
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

    // ── Analyze performance ──────────────────────────────────

    fun analyzePerformance(): String {
        val activities = try {
            stravaService.getRecentActivities(perPage = 20)
                .map { stravaService.toActivitySummary(it) }
        } catch (e: Exception) {
            return "No se pudieron cargar las actividades de Strava."
        }

        val systemPrompt = """
            Eres un entrenador deportivo de élite especializado en análisis de rendimiento.
            Responde en español, de forma clara y accionable, máximo 4 párrafos cortos.
            Usa emojis para hacer el análisis más visual y fácil de leer.
        """.trimIndent()

        val userMessage = """
            Analiza el rendimiento de este atleta basándote en sus últimas actividades:
            ${activities.take(10).joinToString("\n") {
                "- ${it.date}: ${it.type} ${it.distanceKm}km, ${it.movingTimeFormatted}, " +
                "TSS: ${it.tss}, FC: ${it.avgHeartrate?.toInt() ?: "N/A"} bpm, " +
                "Potencia: ${it.avgWatts?.toInt() ?: "N/A"}W"
            }}

            Identifica: tendencias, puntos fuertes, áreas de mejora y 2-3 recomendaciones concretas.
        """.trimIndent()

        return callAnthropic(systemPrompt, listOf(ChatMessage(role = "user", content = userMessage)))
    }

    // ── Private helpers ──────────────────────────────────────

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
        val requestBody = AnthropicRequest(
            model = model,
            max_tokens = 1500,
            system = systemPrompt,
            messages = messages
        )

        val response = webClient.post()
            .uri(apiUrl)
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .bodyValue(requestBody)
            .retrieve()
            .bodyToMono(AnthropicResponse::class.java)
            .block() ?: throw RuntimeException("No response from Anthropic")

        return response.content.firstOrNull()?.text
            ?: throw RuntimeException("Empty response from Anthropic")
    }

    private fun buildCoachSystemPrompt(activities: List<ActivitySummary>): String {
        val weekTss = activities.take(7).sumOf { it.tss ?: 0 }
        val ctlEstimate = activities.take(42).sumOf { it.tss ?: 0 } / 42.0

        return """
            Eres un entrenador deportivo de élite personalizado. Conoces los datos reales del atleta.

            DATOS DEL ATLETA:
            - CTL estimado: ${ctlEstimate.toInt()} pts (forma física)
            - TSS esta semana: $weekTss pts
            - Últimas actividades:
            ${activities.take(6).joinToString("\n") {
                "  • ${it.date} ${it.type}: ${it.distanceKm}km, ${it.movingTimeFormatted}, TSS ${it.tss}"
            }}

            INSTRUCCIONES:
            - Responde en español, tono motivador pero directo
            - Usa los datos reales para personalizar cada respuesta
            - Sé conciso: máximo 3 párrafos
            - Si preguntan sobre nutrición, hidratación o lesiones, recomienda consultar un profesional
        """.trimIndent()
    }
}
