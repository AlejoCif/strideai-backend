package com.strideai.service

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.strideai.dto.*
import com.strideai.repository.TrainingPlanRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentCaptor
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.test.util.ReflectionTestUtils
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono

@ExtendWith(MockitoExtension::class)
class AIServiceTest {

    @Mock
    private lateinit var webClient: WebClient

    @Mock
    private lateinit var stravaService: StravaService

    @Mock
    private lateinit var trainingPlanRepository: TrainingPlanRepository

    @Mock
    private lateinit var requestBodyUriSpec: WebClient.RequestBodyUriSpec

    @Mock
    private lateinit var requestBodySpec: WebClient.RequestBodySpec

    @Mock
    private lateinit var responseSpec: WebClient.ResponseSpec

    @Suppress("UNCHECKED_CAST")
    private val requestHeadersSpec: WebClient.RequestHeadersSpec<*> =
        Mockito.mock(WebClient.RequestHeadersSpec::class.java) as WebClient.RequestHeadersSpec<*>

    private val objectMapper: ObjectMapper = ObjectMapper()
        .registerKotlinModule()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    private lateinit var aiService: AIService

    @BeforeEach
    fun setUp() {
        aiService = AIService(webClient, stravaService, trainingPlanRepository, objectMapper)
        ReflectionTestUtils.setField(aiService, "apiKey", "test-key")
        ReflectionTestUtils.setField(aiService, "apiUrl", "https://api.anthropic.com/v1/messages")
        ReflectionTestUtils.setField(aiService, "model", "claude-sonnet-4-20250514")
    }

    @Test
    fun `chat returns reply from Anthropic`() {
        val stravaActivity = stravaActivityDto()
        val summary = activitySummary()

        Mockito.`when`(stravaService.getRecentActivities(10, 1)).thenReturn(listOf(stravaActivity))
        Mockito.`when`(stravaService.toActivitySummary(stravaActivity)).thenReturn(summary)
        stubWebClient(AnthropicResponse(listOf(AnthropicContent("text", "Vas muy bien!"))))

        val result = aiService.chat(ChatRequest(message = "¿Cómo va mi entrenamiento?"))

        assertThat(result.reply).isEqualTo("Vas muy bien!")
    }

    @Test
    fun `chat builds system prompt containing athlete activity data`() {
        val stravaActivity = stravaActivityDto()
        val summary = activitySummary()

        Mockito.`when`(stravaService.getRecentActivities(10, 1)).thenReturn(listOf(stravaActivity))
        Mockito.`when`(stravaService.toActivitySummary(stravaActivity)).thenReturn(summary)
        stubWebClient(AnthropicResponse(listOf(AnthropicContent("text", "respuesta"))))

        val bodyCaptor = ArgumentCaptor.forClass(Any::class.java)

        aiService.chat(ChatRequest(message = "test"))

        Mockito.verify(requestBodySpec).bodyValue(bodyCaptor.capture())
        val captured = bodyCaptor.value as AnthropicRequest

        // System prompt must include fitness context
        assertThat(captured.system).contains("CTL")
        assertThat(captured.system).contains("TSS")
        assertThat(captured.system).contains("entrenador")

        // Message must be the user's input
        assertThat(captured.messages).hasSize(1)
        assertThat(captured.messages[0].role).isEqualTo("user")
        assertThat(captured.messages[0].content).isEqualTo("test")
    }

    @Test
    fun `chat preserves conversation history`() {
        Mockito.`when`(stravaService.getRecentActivities(10, 1)).thenReturn(emptyList())
        stubWebClient(AnthropicResponse(listOf(AnthropicContent("text", "ok"))))

        val history = listOf(
            ChatMessage(role = "user", content = "mensaje anterior"),
            ChatMessage(role = "assistant", content = "respuesta anterior")
        )

        val bodyCaptor = ArgumentCaptor.forClass(Any::class.java)
        aiService.chat(ChatRequest(message = "nuevo mensaje", conversationHistory = history))

        Mockito.verify(requestBodySpec).bodyValue(bodyCaptor.capture())
        val captured = bodyCaptor.value as AnthropicRequest

        // Should have: 2 history + 1 new user message
        assertThat(captured.messages).hasSize(3)
        assertThat(captured.messages.last().content).isEqualTo("nuevo mensaje")
    }

    // ── Helpers ───────────────────────────────────────────────

    private fun stubWebClient(response: AnthropicResponse) {
        Mockito.`when`(webClient.post()).thenReturn(requestBodyUriSpec)
        Mockito.`when`(requestBodyUriSpec.uri(Mockito.anyString())).thenReturn(requestBodySpec)
        Mockito.`when`(requestBodySpec.header(Mockito.anyString(), Mockito.anyString()))
            .thenReturn(requestBodySpec)
        Mockito.`when`(requestBodySpec.bodyValue(Mockito.any())).thenReturn(requestHeadersSpec)
        Mockito.`when`(requestHeadersSpec.retrieve()).thenReturn(responseSpec)
        // AIService uses bodyToMono(Class<T>) — stub the Class-based overload directly
        Mockito.`when`(responseSpec.bodyToMono(AnthropicResponse::class.java))
            .thenReturn(Mono.just(response))
    }

    private fun stravaActivityDto() = StravaActivityDto(
        id = 1L,
        name = "Morning Run",
        type = "Run",
        sport_type = "Run",
        distance = 10000.0,
        moving_time = 3600,
        elapsed_time = 3700,
        total_elevation_gain = 100.0,
        average_speed = 2.78,
        max_speed = 4.0,
        average_heartrate = 145.0,
        max_heartrate = 165.0,
        average_watts = null,
        weighted_average_watts = null,
        start_date = "2024-01-15T08:00:00Z",
        start_date_local = "2024-01-15T09:00:00",
        timezone = "Europe/Madrid",
        kudos_count = 5,
        achievement_count = 1,
        pr_count = 0,
        suffer_score = 65
    )

    private fun activitySummary() = ActivitySummary(
        stravaId = 1L,
        name = "Morning Run",
        type = "Run",
        date = "2024-01-15",
        distanceKm = 10.0,
        movingTimeFormatted = "1h 0m",
        elevationGain = 100.0,
        avgHeartrate = 145.0,
        avgWatts = null,
        tss = 65
    )
}
