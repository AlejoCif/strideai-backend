package com.strideai.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.strideai.dto.AnthropicRequest
import com.strideai.dto.AnthropicResponse
import com.strideai.dto.ChatMessage
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import org.springframework.web.reactive.function.client.bodyToMono

@Component
class AnthropicAIProvider(
    private val webClient: WebClient,
    private val objectMapper: ObjectMapper
) : AIProvider {

    @Value("\${app.anthropic.api-key}") private lateinit var apiKey: String
    @Value("\${app.anthropic.api-url}") private lateinit var apiUrl: String
    @Value("\${app.anthropic.model}") private lateinit var model: String

    override fun generate(systemPrompt: String, messages: List<ChatMessage>, maxTokens: Int): String {
        val safeMessages = sanitize(messages)
        if (safeMessages.isEmpty()) throw RuntimeException("Cannot call Anthropic with empty messages")

        val requestBody = AnthropicRequest(
            model = model,
            max_tokens = maxTokens,
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
            val body = e.responseBodyAsString
            println("ANTHROPIC ERROR STATUS=${e.statusCode}")
            println("ANTHROPIC ERROR BODY=$body")
            if (body.contains("credit balance", ignoreCase = true) ||
                body.contains("rate_limit", ignoreCase = true)
            ) {
                throw RuntimeException("AI_UNAVAILABLE")
            }
            throw e
        } ?: throw RuntimeException("No response from Anthropic")

        return response.content.firstOrNull()?.text
            ?: throw RuntimeException("Empty response from Anthropic")
    }

    private fun sanitize(messages: List<ChatMessage>) = messages
        .filter { it.content.isNotBlank() }
        .map {
            ChatMessage(
                role = if (it.role == "assistant") "assistant" else "user",
                content = it.content.trim()
            )
        }
}
