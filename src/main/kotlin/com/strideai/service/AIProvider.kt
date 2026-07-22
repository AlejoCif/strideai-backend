package com.strideai.service

import com.strideai.dto.ChatMessage

data class ProviderResult(
    val text: String,
    val inputTokens: Int,
    val outputTokens: Int,
    val provider: String
)

interface AIProvider {
    fun generate(systemPrompt: String, messages: List<ChatMessage>, maxTokens: Int = 1500): ProviderResult
}
