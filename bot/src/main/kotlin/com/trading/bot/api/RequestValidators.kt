package com.trading.bot.api

import com.trading.bot.domain.TradeSide
import com.trading.common.domain.Exchange
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
        // bcrypt 입력 한계는 UTF-8 바이트 기준이다. 한글은 글자당 3바이트라 72자 이하도 넘을 수 있다.
        if (password.toByteArray(Charsets.UTF_8).size > MAX_PASSWORD_BYTES) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Password must be at most $MAX_PASSWORD_BYTES bytes in UTF-8 (non-ASCII characters take 2-4 bytes each)",
            )
        }
    }

    fun normalizeApiKey(value: String, fieldName: String): String {
        val normalized = value.trim()
        if (normalized.isBlank()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "$fieldName is required")
        }
        // HS256 서명에는 32바이트 이상의 secret 이 필요하다. 등록 때 막아야 주문 시점에 WeakKeyException 이 나지 않는다.
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

    /** 값이 없으면 UPBIT 다. 파라미터 없이 오는 기존 요청이 Upbit 를 가리킨다. 철자는 enum 이름과 정확히 같아야 한다. */
    fun parseExchange(raw: String?): Exchange {
        if (raw.isNullOrEmpty()) return Exchange.UPBIT
        return Exchange.entries.firstOrNull { it.name == raw }
            ?: throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "exchange 는 ${Exchange.entries.joinToString { it.name }} 중 하나여야 한다",
            )
    }

    fun normalizeMarkets(exchange: Exchange, markets: List<String>): List<String> = when (exchange) {
        Exchange.UPBIT -> normalizeMarkets(markets)
        Exchange.KIS_PAPER, Exchange.KIS -> {
            val normalized = markets.map(::normalizeKrxMarket).distinct()
            if (normalized.isEmpty() || normalized.size > 20) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Tickers must contain between 1 and 20 markets")
            }
            normalized
        }
    }

    private fun normalizeKrxMarket(market: String): String {
        val normalized = market.trim().uppercase()
        if (!KRX_MARKET_REGEX.matches(normalized)) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid market format")
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
        // 영문이 섞인 신규 종목코드와 7자리 코드(ETN 등)를 받는다.
        private val KRX_MARKET_REGEX = Regex("^KRX-[0-9A-Z]{6,7}$")
        private val ALLOWED_DISCORD_HOSTS = setOf("discord.com", "discordapp.com", "ptb.discord.com", "canary.discord.com")
        private const val MIN_PASSWORD_LENGTH = 10
        private const val MAX_PASSWORD_BYTES = 72
    }
}
