package com.strideai.exception

import com.strideai.dto.ErrorResponse
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.reactive.function.client.WebClientRequestException
import org.springframework.web.reactive.function.client.WebClientResponseException
import java.time.Instant

@RestControllerAdvice
class GlobalExceptionHandler {

    @ExceptionHandler(WebClientResponseException::class)
    fun handleWebClientResponseException(e: WebClientResponseException): ResponseEntity<ErrorResponse> {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(
            ErrorResponse(
                error = "EXTERNAL_API_ERROR",
                message = "Error calling external service: ${e.statusCode}",
                timestamp = Instant.now().toString()
            )
        )
    }

    @ExceptionHandler(WebClientRequestException::class)
    fun handleWebClientRequestException(e: WebClientRequestException): ResponseEntity<ErrorResponse> {
        return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT).body(
            ErrorResponse(
                error = "TIMEOUT_ERROR",
                message = "External service timeout or connection refused",
                timestamp = Instant.now().toString()
            )
        )
    }

    @ExceptionHandler(RuntimeException::class)
    fun handleRuntimeException(e: RuntimeException): ResponseEntity<ErrorResponse> {
        val (status, error, message) = when (e.message) {
            "STRAVA_AUTH_REVOKED" -> Triple(
                HttpStatus.UNAUTHORIZED,
                "STRAVA_AUTH_REVOKED",
                "Tu sesión de Strava ha expirado o fue revocada. Por favor vuelve a iniciar sesión."
            )
            "STRAVA_RATE_LIMITED" -> Triple(
                HttpStatus.TOO_MANY_REQUESTS,
                "STRAVA_RATE_LIMITED",
                "Strava está limitando las solicitudes temporalmente. Intenta de nuevo en 15 minutos."
            )
            else -> Triple(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "INTERNAL_ERROR",
                e.message ?: "An unexpected error occurred"
            )
        }
        return ResponseEntity.status(status).body(
            ErrorResponse(error = error, message = message, timestamp = Instant.now().toString())
        )
    }
}
