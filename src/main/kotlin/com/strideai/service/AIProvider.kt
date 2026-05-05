package com.strideai.service

import com.strideai.dto.ChatMessage

interface AIProvider {
    fun generate(systemPrompt: String, messages: List<ChatMessage>, maxTokens: Int = 1500): String
}
