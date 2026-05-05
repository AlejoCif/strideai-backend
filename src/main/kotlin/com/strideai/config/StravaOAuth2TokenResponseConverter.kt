package com.strideai.config

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.core.convert.converter.Converter
import org.springframework.security.oauth2.core.OAuth2AccessToken
import org.springframework.security.oauth2.core.endpoint.OAuth2AccessTokenResponse
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames

private val STANDARD_PARAMS = setOf(
    OAuth2ParameterNames.ACCESS_TOKEN,
    OAuth2ParameterNames.TOKEN_TYPE,
    OAuth2ParameterNames.EXPIRES_IN,
    OAuth2ParameterNames.REFRESH_TOKEN,
    OAuth2ParameterNames.SCOPE,
    "expires_at"
)

class StravaOAuth2TokenResponseConverter : Converter<Map<String, Any>, OAuth2AccessTokenResponse> {

    private val mapper = ObjectMapper()

    override fun convert(source: Map<String, Any>): OAuth2AccessTokenResponse {
        val accessToken = requireNotNull(source[OAuth2ParameterNames.ACCESS_TOKEN]?.toString()) {
            "Missing access_token in Strava token response"
        }

        val expiresIn = resolveExpiresIn(source)
        val refreshToken = source[OAuth2ParameterNames.REFRESH_TOKEN]?.toString()

        val scopes = source[OAuth2ParameterNames.SCOPE]?.toString()
            ?.split(",", " ")
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?.toSet()
            ?: emptySet()

        // Carry non-standard fields (including athlete JSON) as additional parameters
        val additional = source
            .filterKeys { it !in STANDARD_PARAMS }
            .mapValues { (_, v) ->
                if (v is Map<*, *> || v is List<*>) mapper.writeValueAsString(v) else v.toString()
            }

        return OAuth2AccessTokenResponse.withToken(accessToken)
            .tokenType(OAuth2AccessToken.TokenType.BEARER)
            .expiresIn(expiresIn)
            .apply { if (refreshToken != null) refreshToken(refreshToken) }
            .scopes(scopes)
            .additionalParameters(additional)
            .build()
    }

    private fun resolveExpiresIn(source: Map<String, Any>): Long {
        source[OAuth2ParameterNames.EXPIRES_IN]?.toString()?.toLongOrNull()?.let { return it }
        // Strava uses expires_at (epoch seconds) instead of expires_in
        val expiresAt = source["expires_at"]?.toString()?.toLongOrNull() ?: return 0L
        return maxOf(0L, expiresAt - System.currentTimeMillis() / 1000)
    }
}
