package com.strideai.security

import io.jsonwebtoken.Claims
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Date

@Service
class JwtService {

    @Value("\${app.jwt-secret}") private lateinit var secret: String

    private val key by lazy {
        Keys.hmacShaKeyFor(secret.toByteArray(Charsets.UTF_8))
    }

    fun generateToken(userId: Long, stravaId: Long): String =
        Jwts.builder()
            .subject(userId.toString())
            .claim("stravaId", stravaId)
            .issuedAt(Date.from(Instant.now()))
            .expiration(Date.from(Instant.now().plus(30, ChronoUnit.DAYS)))
            .signWith(key)
            .compact()

    fun validateToken(token: String): Claims? = try {
        Jwts.parser()
            .verifyWith(key)
            .build()
            .parseSignedClaims(token)
            .payload
    } catch (_: Exception) {
        null
    }
}
