package com.trading.bot.api

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException
import java.net.URI
import kotlin.math.absoluteValue

@Component
class RequestValidators {
    fun normalizeUsername(username: String): String {
        val normalized = username.trim()
        if (!USERNAME_REGEX.matches(normalized)) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Username must be 3-30 chars and contain only letters, numbers, underscore, dash",
            )
        }
        return normalized
    }

    fun validatePassword(password: String) {
        if (password.length < 8) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Password must be at least 8 characters")
        }
    }

    fun normalizeApiKey(value: String, fieldName: String): String {
        val normalized = value.trim()
        if (normalized.isBlank()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "$fieldName is required")
        }
        if (normalized.length !in 16..128 || !API_KEY_REGEX.matches(normalized)) {
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

    fun normalizeMarkets(markets: List<String>): List<String> {
        val normalized = markets.map(::normalizeMarket).distinct()
        if (normalized.isEmpty() || normalized.size > 20) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Tickers must contain between 1 and 20 markets")
        }
        return normalized
    }

    fun normalizeStrategy(strategy: String): String {
        val normalized = strategy.trim()
        if (normalized.isBlank() || normalized.length > 100) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid strategy name")
        }
        return normalized
    }

    fun validateOrderAmount(amount: Double) {
        if (!amount.isFinite() || amount < 5_000) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Minimum order: 5,000 KRW")
        }
    }

    fun normalizeSellVolume(volume: String): String {
        val normalized = volume.trim()
        val numeric = normalized.toDoubleOrNull()
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid volume format")
        if (!numeric.isFinite() || numeric <= 0.0) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Volume must be greater than 0")
        }
        return if (numeric.absoluteValue >= 1e-6) normalized else throw ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            "Volume is too small",
        )
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
        private val USERNAME_REGEX = Regex("^[A-Za-z0-9_-]{3,30}$")
        private val API_KEY_REGEX = Regex("^[A-Za-z0-9_-]+$")
        private val MARKET_REGEX = Regex("^[A-Z]{2,10}-[A-Z0-9]{2,20}$")
        private val ALLOWED_DISCORD_HOSTS = setOf("discord.com", "discordapp.com", "ptb.discord.com", "canary.discord.com")
    }
}
