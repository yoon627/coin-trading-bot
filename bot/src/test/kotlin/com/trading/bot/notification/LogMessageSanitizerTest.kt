package com.trading.bot.notification

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LogMessageSanitizerTest {

    @Test
    fun `Bearer 토큰 마스킹`() {
        val r = LogMessageSanitizer.sanitize("Authorization: Bearer abc123XYZ.token_value")
        assertFalse(r.contains("abc123XYZ"))
        assertTrue(r.contains("Bearer"))
    }

    @Test
    fun `JWT 마스킹`() {
        val jwt = "eyJhbGciOiJIUzI1Ni3.eyJzdWIiOiIxMjM0NTY.SflKxwRJSMeKKF2QT4fwpM"
        val r = LogMessageSanitizer.sanitize("upbit jwt token=$jwt end")
        assertFalse(r.contains(jwt))
    }

    @Test
    fun `access_key secret_key password 값 마스킹`() {
        val r = LogMessageSanitizer.sanitize("""{"access_key":"AKIA123ABC","secret_key":"sk_live_abcdef","password":"hunter2"}""")
        assertFalse(r.contains("AKIA123ABC"))
        assertFalse(r.contains("sk_live_abcdef"))
        assertFalse(r.contains("hunter2"))
    }

    @Test
    fun `discord webhook URL 토큰 마스킹`() {
        val r = LogMessageSanitizer.sanitize("posting to https://discord.com/api/webhooks/123456789/AbCdEf-SecretToken123 failed")
        assertFalse(r.contains("AbCdEf-SecretToken123"))
    }

    @Test
    fun `평범한 메시지는 그대로 보존`() {
        val msg = "Candle collection failed for NEAR/KRW: 429 Too Many Requests"
        assertEquals(msg, LogMessageSanitizer.sanitize(msg))
    }

    @Test
    fun `null 이나 빈 문자열 안전 처리`() {
        assertEquals("", LogMessageSanitizer.sanitize(""))
    }

    @Test
    fun `공백으로 구분된 access_key 도 마스킹`() {
        val r = LogMessageSanitizer.sanitize("access_key abcd1234efgh value")
        assertFalse(r.contains("abcd1234efgh"))
    }

    @Test
    fun `base64 문자 포함 Bearer 토큰 전체 마스킹`() {
        val r = LogMessageSanitizer.sanitize("Bearer abc+def/ghi==")
        assertFalse(r.contains("def/ghi"))
        assertFalse(r.contains("abc+def"))
    }
}
