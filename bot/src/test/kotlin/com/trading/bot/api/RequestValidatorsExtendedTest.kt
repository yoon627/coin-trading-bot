package com.trading.bot.api

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.web.server.ResponseStatusException

class RequestValidatorsExtendedTest {

    private val validators = RequestValidators()

    // --- normalizeUsername ---

    @Test
    fun `normalizeUsername accepts valid usernames`() {
        assertEquals("testuser", validators.normalizeUsername("testuser"))
        assertEquals("test-user", validators.normalizeUsername("test-user"))
        assertEquals("test_user", validators.normalizeUsername("test_user"))
        assertEquals("user123", validators.normalizeUsername("user123"))
    }

    @Test
    fun `normalizeUsername lowercases input`() {
        assertEquals("testuser", validators.normalizeUsername("TestUser"))
    }

    @Test
    fun `normalizeUsername trims whitespace`() {
        assertEquals("testuser", validators.normalizeUsername("  testuser  "))
    }

    @Test
    fun `normalizeUsername rejects too short`() {
        assertThrows<ResponseStatusException> { validators.normalizeUsername("ab") }
    }

    @Test
    fun `normalizeUsername rejects too long`() {
        val longName = "a".repeat(31)
        assertThrows<ResponseStatusException> { validators.normalizeUsername(longName) }
    }

    @Test
    fun `normalizeUsername rejects special characters`() {
        assertThrows<ResponseStatusException> { validators.normalizeUsername("test@user") }
        assertThrows<ResponseStatusException> { validators.normalizeUsername("test user") }
        assertThrows<ResponseStatusException> { validators.normalizeUsername("test.user") }
    }

    // --- validatePassword ---

    @Test
    fun `validatePassword accepts passwords within length bounds`() {
        assertDoesNotThrow { validators.validatePassword("1234567890") } // 10 chars (min)
        assertDoesNotThrow { validators.validatePassword("a very long password with spaces") }
    }

    @Test
    fun `validatePassword rejects short passwords`() {
        assertThrows<ResponseStatusException> { validators.validatePassword("123456789") } // 9 < min 10
        assertThrows<ResponseStatusException> { validators.validatePassword("") }
    }

    @Test
    fun `validatePassword rejects overly long passwords`() {
        assertThrows<ResponseStatusException> { validators.validatePassword("a".repeat(73)) } // > 72 (bcrypt limit)
    }

    // --- normalizeApiKey ---

    @Test
    fun `normalizeApiKey accepts valid keys`() {
        val key = "ABCDEFGHIJKLMNOPq" // 17 chars
        assertEquals(key, validators.normalizeApiKey(key, "test"))
    }

    @Test
    fun `normalizeApiKey rejects too short keys`() {
        assertThrows<ResponseStatusException> { validators.normalizeApiKey("short", "test") }
    }

    @Test
    fun `normalizeApiKey rejects keys with invalid chars`() {
        assertThrows<ResponseStatusException> { validators.normalizeApiKey("abcdefghijklmnop!", "test") }
    }

    @Test
    fun `normalizeApiKey rejects blank keys`() {
        assertThrows<ResponseStatusException> { validators.normalizeApiKey("", "test") }
        assertThrows<ResponseStatusException> { validators.normalizeApiKey("   ", "test") }
    }

    // --- normalizeMarket ---

    @Test
    fun `normalizeMarket accepts valid formats`() {
        assertEquals("KRW-BTC", validators.normalizeMarket("KRW-BTC"))
        assertEquals("KRW-ETH", validators.normalizeMarket("krw-eth"))
        assertEquals("USDT-BTC", validators.normalizeMarket("usdt-btc"))
    }

    @Test
    fun `normalizeMarket rejects invalid formats`() {
        assertThrows<ResponseStatusException> { validators.normalizeMarket("BTCKRW") }
        assertThrows<ResponseStatusException> { validators.normalizeMarket("KRW_BTC") }
        assertThrows<ResponseStatusException> { validators.normalizeMarket("") }
    }

    // --- normalizeMarkets ---

    @Test
    fun `normalizeMarkets accepts valid list`() {
        val result = validators.normalizeMarkets(listOf("KRW-BTC", "KRW-ETH"))
        assertEquals(listOf("KRW-BTC", "KRW-ETH"), result)
    }

    @Test
    fun `normalizeMarkets deduplicates`() {
        val result = validators.normalizeMarkets(listOf("KRW-BTC", "krw-btc"))
        assertEquals(1, result.size)
    }

    @Test
    fun `normalizeMarkets rejects empty list`() {
        assertThrows<ResponseStatusException> { validators.normalizeMarkets(emptyList()) }
    }

    @Test
    fun `normalizeMarkets rejects more than 20 markets`() {
        val markets = (1..21).map { "KRW-COIN$it" }
        assertThrows<ResponseStatusException> { validators.normalizeMarkets(markets) }
    }

    // --- normalizeStrategy ---

    @Test
    fun `normalizeStrategy accepts valid names`() {
        assertEquals("volatility_breakout", validators.normalizeStrategy("volatility_breakout"))
    }

    @Test
    fun `normalizeStrategy rejects blank`() {
        assertThrows<ResponseStatusException> { validators.normalizeStrategy("") }
        assertThrows<ResponseStatusException> { validators.normalizeStrategy("   ") }
    }

    // --- validateOrderAmount ---

    @Test
    fun `validateOrderAmount accepts valid amounts`() {
        assertDoesNotThrow { validators.validateOrderAmount(5000.0) }
        assertDoesNotThrow { validators.validateOrderAmount(100000.0) }
        assertDoesNotThrow { validators.validateOrderAmount(10000000.0) }
    }

    @Test
    fun `validateOrderAmount rejects below minimum`() {
        assertThrows<ResponseStatusException> { validators.validateOrderAmount(4999.0) }
        assertThrows<ResponseStatusException> { validators.validateOrderAmount(0.0) }
        assertThrows<ResponseStatusException> { validators.validateOrderAmount(-1000.0) }
    }

    @Test
    fun `validateOrderAmount rejects above maximum`() {
        assertThrows<ResponseStatusException> { validators.validateOrderAmount(10000001.0) }
    }

    @Test
    fun `validateOrderAmount rejects NaN and Infinity`() {
        assertThrows<ResponseStatusException> { validators.validateOrderAmount(Double.NaN) }
        assertThrows<ResponseStatusException> { validators.validateOrderAmount(Double.POSITIVE_INFINITY) }
    }

    // --- normalizeSellVolume ---

    @Test
    fun `normalizeSellVolume accepts valid volumes`() {
        assertEquals("0.001", validators.normalizeSellVolume("0.001"))
        assertEquals("1.5", validators.normalizeSellVolume("1.5"))
    }

    @Test
    fun `normalizeSellVolume rejects non-numeric`() {
        assertThrows<ResponseStatusException> { validators.normalizeSellVolume("abc") }
    }

    @Test
    fun `normalizeSellVolume rejects zero and negative`() {
        assertThrows<ResponseStatusException> { validators.normalizeSellVolume("0") }
        assertThrows<ResponseStatusException> { validators.normalizeSellVolume("-1") }
    }

    // --- normalizeDiscordWebhookUrl ---

    @Test
    fun `normalizeDiscordWebhookUrl accepts valid Discord URLs`() {
        val url = "https://discord.com/api/webhooks/123456/abcdef"
        assertEquals(url, validators.normalizeDiscordWebhookUrl(url))
    }

    @Test
    fun `normalizeDiscordWebhookUrl accepts discordapp domain`() {
        val url = "https://discordapp.com/api/webhooks/123/abc"
        assertEquals(url, validators.normalizeDiscordWebhookUrl(url))
    }

    @Test
    fun `normalizeDiscordWebhookUrl returns null for blank`() {
        assertNull(validators.normalizeDiscordWebhookUrl(""))
        assertNull(validators.normalizeDiscordWebhookUrl(null))
        assertNull(validators.normalizeDiscordWebhookUrl("   "))
    }

    @Test
    fun `normalizeDiscordWebhookUrl rejects non-Discord URLs`() {
        assertThrows<ResponseStatusException> {
            validators.normalizeDiscordWebhookUrl("https://evil.com/api/webhooks/123/abc")
        }
    }

    @Test
    fun `normalizeDiscordWebhookUrl rejects HTTP URLs`() {
        assertThrows<ResponseStatusException> {
            validators.normalizeDiscordWebhookUrl("http://discord.com/api/webhooks/123/abc")
        }
    }

    @Test
    fun `normalizeDiscordWebhookUrl rejects non-webhook paths`() {
        assertThrows<ResponseStatusException> {
            validators.normalizeDiscordWebhookUrl("https://discord.com/channels/123")
        }
    }

    // --- sanitizeTradeLimit ---

    @Test
    fun `sanitizeTradeLimit clamps values`() {
        assertEquals(1, validators.sanitizeTradeLimit(0))
        assertEquals(1, validators.sanitizeTradeLimit(-5))
        assertEquals(50, validators.sanitizeTradeLimit(50))
        assertEquals(500, validators.sanitizeTradeLimit(1000))
    }
}
