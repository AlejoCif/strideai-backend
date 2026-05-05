package com.strideai.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.converter.FormHttpMessageConverter
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.oauth2.client.endpoint.DefaultAuthorizationCodeTokenResponseClient
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter
import org.springframework.security.web.SecurityFilterChain
import org.springframework.web.client.RestTemplate
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource

@Configuration
@EnableWebSecurity
class SecurityConfig(
    @Value("\${app.frontend-url}") private val frontendUrl: String
) {

    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .cors { it.configurationSource(corsConfigurationSource()) }
            .csrf { it.disable() }
            .authorizeHttpRequests { auth ->
                auth
                    .requestMatchers("/api/health").permitAll()
                    .requestMatchers("/login/**", "/oauth2/**").permitAll()
                    .requestMatchers("/api/**").authenticated()
            }
            .oauth2Login { oauth2 ->
                oauth2
                    .tokenEndpoint { it.accessTokenResponseClient(stravaTokenResponseClient()) }
                    .defaultSuccessUrl("$frontendUrl/dashboard", true)
                    .failureUrl("$frontendUrl/login?error")
            }
            .logout { logout ->
                logout
                    .logoutSuccessUrl(frontendUrl)
                    .invalidateHttpSession(true)
                    .deleteCookies("JSESSIONID")
            }

        return http.build()
    }

    private fun stravaTokenResponseClient(): DefaultAuthorizationCodeTokenResponseClient {
        val tokenResponseConverter = OAuth2AccessTokenResponseHttpMessageConverter()
        tokenResponseConverter.setAccessTokenResponseConverter(StravaOAuth2TokenResponseConverter())

        val restTemplate = RestTemplate(listOf(FormHttpMessageConverter(), tokenResponseConverter))
        restTemplate.errorHandler = OAuth2ErrorResponseErrorHandler()

        return DefaultAuthorizationCodeTokenResponseClient().apply {
            setRestOperations(restTemplate)
        }
    }

    @Bean
    fun corsConfigurationSource(): CorsConfigurationSource {
        val config = CorsConfiguration()
        config.allowedOrigins = listOf(frontendUrl)
        config.allowedMethods = listOf("GET", "POST", "PUT", "DELETE", "OPTIONS")
        config.allowedHeaders = listOf("*")
        config.allowCredentials = true

        val source = UrlBasedCorsConfigurationSource()
        source.registerCorsConfiguration("/**", config)
        return source
    }
}
