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
    fun `appkey appsecret JSON 형 마스킹`() {
        val r = LogMessageSanitizer.sanitize("""{"appkey":"PSxyz123","appsecret":"abc+def/secret180=="}""")
        assertFalse(r.contains("PSxyz123"))
        assertFalse(r.contains("abc+def/secret180"))
    }

    @Test
    fun `appkey appsecret 헤더형 마스킹`() {
        val r = LogMessageSanitizer.sanitize("appkey: PSrealkeyvalue appsecret: SECRETvalue123")
        assertFalse(r.contains("PSrealkeyvalue"))
        assertFalse(r.contains("SECRETvalue123"))
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
    fun `KIS access_token 값 마스킹, 만료 시각은 보존`() {
        val r = LogMessageSanitizer.sanitize(
            """{"access_token":"tokVALUE123.abc","access_token_token_expired":"2026-10-22 09:00:00","expires_in":86400}""",
        )
        assertFalse(r.contains("tokVALUE123"))
        assertTrue(r.contains("2026-10-22 09:00:00"))
    }

    @Test
    fun `KIS CANO 값 쿼리형 JSON형 마스킹`() {
        val query = LogMessageSanitizer.sanitize("GET /uapi/domestic-stock/v1/trading/inquire-balance?CANO=12345678&ACNT_PRDT_CD=01&PDNO=005930")
        assertFalse(query.contains("12345678"))
        assertTrue(query.contains("PDNO=005930"))
        val json = LogMessageSanitizer.sanitize("""{"CANO":"87654321","ACNT_PRDT_CD":"01"}""")
        assertFalse(json.contains("87654321"))
    }

    @Test
    fun `8-2 형태 계좌번호 마스킹, 자릿수가 다르면 보존`() {
        val r = LogMessageSanitizer.sanitize("account 12345678-01 rejected, order 987654321-012")
        assertFalse(r.contains("12345678"))
        assertTrue(r.contains("987654321-012"))
    }

    @Test
    fun `숫자 8자리로 시작하는 Upbit 주문 uuid 는 보존`() {
        val uuid = "12345678-90ab-4fec-9f0a-48cd83c2eaae"
        val r = LogMessageSanitizer.sanitize("order uuid=$uuid not found")
        assertTrue(r.contains(uuid))
    }

    @Test
    fun `base64 문자 포함 Bearer 토큰 전체 마스킹`() {
        val r = LogMessageSanitizer.sanitize("Bearer abc+def/ghi==")
        assertFalse(r.contains("def/ghi"))
        assertFalse(r.contains("abc+def"))
    }
}
