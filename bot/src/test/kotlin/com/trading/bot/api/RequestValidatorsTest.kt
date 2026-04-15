package com.trading.bot.api

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.web.server.ResponseStatusException

class RequestValidatorsTest {
    private val validators = RequestValidators()

    @Test
    fun `normalizes valid market and username`() {
        assertEquals("KRW-BTC", validators.normalizeMarket(" krw-btc "))
        assertEquals("user_01", validators.normalizeUsername(" user_01 "))
    }

    @Test
    fun `rejects invalid discord webhook`() {
        assertThrows<ResponseStatusException> {
            validators.normalizeDiscordWebhookUrl("https://example.com/api/webhooks/123")
        }
    }

    @Test
    fun `blank discord webhook clears configuration`() {
        assertNull(validators.normalizeDiscordWebhookUrl("   "))
    }
}
