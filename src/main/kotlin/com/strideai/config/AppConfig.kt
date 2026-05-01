package com.strideai.config

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.MediaType
import org.springframework.http.codec.json.Jackson2JsonDecoder
import org.springframework.http.codec.json.Jackson2JsonEncoder
import org.springframework.web.reactive.function.client.WebClient

@Configuration
class AppConfig {

    @Bean
    fun objectMapper(): ObjectMapper = ObjectMapper()
        .registerKotlinModule()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    @Bean
    fun webClient(objectMapper: ObjectMapper): WebClient {
        return WebClient.builder()
            .codecs { codecs ->
                codecs.defaultCodecs().jackson2JsonEncoder(
                    Jackson2JsonEncoder(objectMapper, MediaType.APPLICATION_JSON)
                )
                codecs.defaultCodecs().jackson2JsonDecoder(
                    Jackson2JsonDecoder(objectMapper, MediaType.APPLICATION_JSON)
                )
                codecs.defaultCodecs().maxInMemorySize(2 * 1024 * 1024)
            }
            .defaultHeader("Content-Type", "application/json")
            .defaultHeader("Accept", "application/json")
            .build()
    }
}
