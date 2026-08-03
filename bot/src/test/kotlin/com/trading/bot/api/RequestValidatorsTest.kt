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

    @Test
    fun `accepts realistic KIS appsecret with base64 chars`() {
        // KIS appsecret ~180자 base64류(+/=) — 오거부되면 안 됨.
        val secret = "ab+cd/ef=" + "X".repeat(170)
        assertEquals(secret, validators.normalizeKisAppSecret(secret))
    }

    @Test
    fun `rejects non-ASCII or whitespace KIS key (graceful 400, no column overflow)`() {
        assertThrows<ResponseStatusException> { validators.normalizeKisAppSecret("키".repeat(20)) }
        assertThrows<ResponseStatusException> { validators.normalizeKisAppKey("has space inside key value") }
    }

    @Test
    fun `validates KIS account number format`() {
        assertEquals("12345678", validators.normalizeKisCano(" 12345678 "))
        assertEquals("01", validators.normalizeKisAcntPrdtCd("01"))
        assertThrows<ResponseStatusException> { validators.normalizeKisCano("123") }
        assertThrows<ResponseStatusException> { validators.normalizeKisAcntPrdtCd("1") }
    }
}
