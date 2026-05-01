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
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
            ErrorResponse(
                error = "INTERNAL_ERROR",
                message = e.message ?: "An unexpected error occurred",
                timestamp = Instant.now().toString()
            )
        )
    }
}
