package com.strideai.controller

import com.fasterxml.jackson.databind.ObjectMapper
import com.strideai.dto.StravaAthleteDto
import com.strideai.dto.StravaTokenResponse
import com.strideai.model.User
import com.strideai.repository.UserRepository
import com.strideai.security.JwtService
import com.strideai.service.StravaService
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.*
import org.springframework.stereotype.Controller
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.bind.annotation.*
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestTemplate
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant

@Controller
class StravaAuthController(
    private val userRepository: UserRepository,
    private val stravaService: StravaService,
    private val jwtService: JwtService,
    private val objectMapper: ObjectMapper
) {
    @Value("\${app.strava.client-id}") private lateinit var clientId: String
    @Value("\${app.strava.client-secret}") private lateinit var clientSecret: String
    @Value("\${app.frontend-url}") private lateinit var frontendUrl: String

    private val callbackUrl = "https://strideai-backend-production.up.railway.app/auth/strava/callback"
    private val restTemplate = RestTemplate()

    // Backward-compat redirect for old Spring Security OAuth2 URL
    @GetMapping("/login/oauth2/authorization/strava", "/oauth2/authorization/strava")
    fun legacyRedirect(): String = "redirect:/auth/strava"

    @GetMapping("/auth/strava")
    fun redirectToStrava(): String {
        val scope = URLEncoder.encode("read,activity:read_all,profile:read_all", StandardCharsets.UTF_8)
        val callback = URLEncoder.encode(callbackUrl, StandardCharsets.UTF_8)
        return "redirect:https://www.strava.com/oauth/authorize" +
            "?client_id=$clientId&redirect_uri=$callback&response_type=code&scope=$scope&approval_prompt=auto"
    }

    @GetMapping("/auth/strava/callback")
    fun handleCallback(
        @RequestParam(required = false) code: String?,
        @RequestParam(required = false) error: String?
    ): String {
        if (error != null || code == null) {
            return "redirect:$frontendUrl/login?error=${error ?: "access_denied"}"
        }

        return try {
            val tokenResponse = exchangeCodeForToken(code)
            val athlete = tokenResponse.athlete
                ?: return "redirect:$frontendUrl/login?error=missing_athlete"

            val user = upsertUser(tokenResponse, athlete)

            stravaService.updateTokenCache(
                tokenResponse.access_token,
                tokenResponse.refresh_token,
                tokenResponse.expires_at
            )

            val jwt = jwtService.generateToken(userId = user.id, stravaId = athlete.id)
            "redirect:$frontendUrl/dashboard?token=$jwt"
        } catch (e: HttpClientErrorException) {
            "redirect:$frontendUrl/login?error=token_exchange_failed"
        } catch (e: Exception) {
            "redirect:$frontendUrl/login?error=auth_error"
        }
    }

    @PostMapping("/auth/logout")
    @ResponseBody
    fun logout(): ResponseEntity<Map<String, Boolean>> =
        ResponseEntity.ok(mapOf("success" to true))

    // ── Private helpers ───────────────────────────────────

    private fun exchangeCodeForToken(code: String): StravaTokenResponse {
        val headers = HttpHeaders().apply { contentType = MediaType.APPLICATION_FORM_URLENCODED }
        val body = LinkedMultiValueMap<String, String>().apply {
            add("client_id", clientId)
            add("client_secret", clientSecret)
            add("code", code)
            add("grant_type", "authorization_code")
        }
        val raw = restTemplate.postForEntity(
            "https://www.strava.com/oauth/token",
            HttpEntity(body, headers),
            String::class.java
        ).body ?: throw RuntimeException("Empty Strava token response")

        return objectMapper.readValue(raw, StravaTokenResponse::class.java)
    }

    private fun upsertUser(token: StravaTokenResponse, athlete: StravaAthleteDto): User {
        val existing = userRepository.findByStravaId(athlete.id)
        val user = if (existing != null) {
            existing.copy(
                accessToken = token.access_token,
                refreshToken = token.refresh_token,
                tokenExpiresAt = token.expires_at,
                updatedAt = Instant.now()
            )
        } else {
            User(
                stravaId = athlete.id,
                firstName = athlete.firstname,
                lastName = athlete.lastname,
                profileImageUrl = athlete.profile,
                city = athlete.city,
                country = athlete.country,
                accessToken = token.access_token,
                refreshToken = token.refresh_token,
                tokenExpiresAt = token.expires_at
            )
        }
        return userRepository.save(user)
    }
}
