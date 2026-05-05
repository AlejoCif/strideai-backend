package com.strideai.service

import com.strideai.dto.ChatMessage
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import org.springframework.web.reactive.function.client.bodyToMono

// ── OpenAI request / response DTOs (internal to provider) ─

private data class OpenAIRequest(
    val model: String,
    val messages: List<OpenAIMessage>,
    val max_tokens: Int
)

private data class OpenAIMessage(val role: String, val content: String)

private data class OpenAIResponse(val choices: List<OpenAIChoice>)
private data class OpenAIChoice(val message: OpenAIMessage)

// ── Provider ──────────────────────────────────────────────

@Component
class OpenAIProvider(private val webClient: WebClient) : AIProvider {

    @Value("\${app.openai.api-key}") private lateinit var apiKey: String
    @Value("\${app.openai.api-url}") private lateinit var apiUrl: String
    @Value("\${app.openai.model}") private lateinit var model: String

    override fun generate(systemPrompt: String, messages: List<ChatMessage>, maxTokens: Int): String {
        val openAiMessages = buildList {
            add(OpenAIMessage(role = "system", content = systemPrompt.trim()))
            messages
                .filter { it.content.isNotBlank() }
                .forEach { msg ->
                    add(OpenAIMessage(
                        role = if (msg.role == "assistant") "assistant" else "user",
                        content = msg.content.trim()
                    ))
                }
        }

        if (openAiMessages.size < 2) throw RuntimeException("Cannot call OpenAI with empty messages")

        val requestBody = OpenAIRequest(model = model, messages = openAiMessages, max_tokens = maxTokens)

        val response = try {
            webClient.post()
                .uri(apiUrl)
                .header("Authorization", "Bearer ${apiKey.trim()}")
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(OpenAIResponse::class.java)
                .block()
        } catch (e: WebClientResponseException) {
            val body = e.responseBodyAsString
            println("OPENAI ERROR STATUS=${e.statusCode}")
            println("OPENAI ERROR BODY=$body")
            if (body.contains("insufficient_quota", ignoreCase = true) ||
                body.contains("rate_limit", ignoreCase = true) ||
                body.contains("billing", ignoreCase = true) ||
                body.contains("quota", ignoreCase = true)
            ) {
                throw RuntimeException("AI_UNAVAILABLE")
            }
            throw e
        } ?: throw RuntimeException("No response from OpenAI")

        return response.choices.firstOrNull()?.message?.content
            ?: throw RuntimeException("Empty response from OpenAI")
    }
}
