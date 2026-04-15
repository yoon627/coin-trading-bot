package com.trading.collector.exchange.kis

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.reactor.awaitSingle
import org.slf4j.LoggerFactory
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToMono
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

class KisAuthProvider(
    private val kisWebClient: WebClient,
    private val appKey: String,
    private val appSecret: String,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val tokenRef = AtomicReference<TokenInfo?>()

    companion object {
        private const val TOKEN_REFRESH_BUFFER_SECONDS = 3600L
    }

    suspend fun getAccessToken(): String {
        val existing = tokenRef.get()
        if (existing != null && existing.expiresAt.isAfter(Instant.now().plusSeconds(TOKEN_REFRESH_BUFFER_SECONDS))) {
            return existing.token
        }
        return refreshToken()
    }

    fun getAppKey(): String = appKey
    fun getAppSecret(): String = appSecret

    private suspend fun refreshToken(): String {
        log.info("Refreshing KIS access token")
        val body = mapOf(
            "grant_type" to "client_credentials",
            "appkey" to appKey,
            "appsecret" to appSecret,
        )

        val response = kisWebClient.post()
            .uri("/oauth2/tokenP")
            .bodyValue(body)
            .retrieve()
            .bodyToMono<String>()
            .awaitSingle()

        val node: JsonNode = objectMapper.readTree(response)
        val accessToken = node["access_token"].asText()
        val expiresIn = node["expires_in"]?.asLong() ?: 86400L

        val tokenInfo = TokenInfo(
            token = accessToken,
            expiresAt = Instant.now().plusSeconds(expiresIn),
        )
        tokenRef.set(tokenInfo)
        log.info("KIS access token refreshed, expires in {}s", expiresIn)
        return accessToken
    }

    private data class TokenInfo(
        val token: String,
        val expiresAt: Instant,
    )
}
