package com.trading.bot.api

import com.trading.bot.domain.TradeSide
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException
import java.net.URI

@Component
class RequestValidators {
    fun normalizeUsername(username: String): String {
        val normalized = username.trim().lowercase()
        if (!USERNAME_REGEX.matches(normalized)) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Username must be 3-30 chars and contain only letters, numbers, underscore, dash",
            )
        }
        return normalized
    }

    fun validatePassword(password: String) {
        if (password.length < MIN_PASSWORD_LENGTH) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Password must be at least $MIN_PASSWORD_LENGTH characters")
        }
        // bcrypt 는 72바이트 초과를 잘라내므로 상한을 둬 silent truncation/해싱 비용 DoS 를 차단.
        if (password.length > MAX_PASSWORD_LENGTH) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Password must be at most $MAX_PASSWORD_LENGTH characters")
        }
    }

    fun normalizeApiKey(value: String, fieldName: String): String {
        val normalized = value.trim()
        if (normalized.isBlank()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "$fieldName is required")
        }
        // 최소 32자 — Upbit secret 으로 HS256 서명 시 256비트(32바이트) 이상이 필요.
        // 짧은 키는 등록 시 400 으로 막아 거래 시점의 WeakKeyException(500)을 예방.
        if (normalized.length !in 32..128 || !API_KEY_REGEX.matches(normalized)) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid $fieldName format")
        }
        return normalized
    }

    fun normalizeMarket(market: String): String {
        val normalized = market.trim().uppercase()
        if (!MARKET_REGEX.matches(normalized)) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid market format")
        }
        return normalized
    }

    fun normalizeTradeSide(side: String): TradeSide = when (side.trim().lowercase()) {
        "buy" -> TradeSide.BUY
        "sell" -> TradeSide.SELL
        else -> throw ResponseStatusException(HttpStatus.BAD_REQUEST, "side must be buy or sell")
    }

    fun normalizeMarkets(markets: List<String>): List<String> {
        val normalized = markets.map(::normalizeMarket).distinct()
        if (normalized.isEmpty() || normalized.size > 20) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Tickers must contain between 1 and 20 markets")
        }
        return normalized
    }

    fun normalizeDiscordWebhookUrl(url: String?): String? {
        val normalized = url?.trim().orEmpty()
        if (normalized.isBlank()) {
            return null
        }
        val uri = try {
            URI(normalized)
        } catch (_: Exception) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid Discord webhook URL")
        }
        val host = uri.host?.lowercase()
        val valid = uri.scheme == "https" &&
            host in ALLOWED_DISCORD_HOSTS &&
            uri.path.startsWith("/api/webhooks/")
        if (!valid) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Only Discord HTTPS webhook URLs are allowed")
        }
        return normalized
    }

    fun sanitizeTradeLimit(limit: Int): Int = limit.coerceIn(1, 500)

    companion object {
        private val USERNAME_REGEX = Regex("^[a-z0-9_-]{3,30}$")
        private val API_KEY_REGEX = Regex("^[A-Za-z0-9_-]+$")
        private val MARKET_REGEX = Regex("^[A-Z]{2,10}-[A-Z0-9]{2,20}$")
        private val ALLOWED_DISCORD_HOSTS = setOf("discord.com", "discordapp.com", "ptb.discord.com", "canary.discord.com")
        private const val MIN_PASSWORD_LENGTH = 10
        private const val MAX_PASSWORD_LENGTH = 72  // bcrypt 입력 한계
    }
}
