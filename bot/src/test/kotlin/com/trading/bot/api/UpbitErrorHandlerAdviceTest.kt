package com.trading.bot.api

import com.trading.bot.client.UpbitApiException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus

class UpbitErrorHandlerAdviceTest {

    private val advice = UpbitErrorHandlerAdvice()

    @Test
    fun `401 no_authorization_ip is mapped to 400 with IP guidance message`() {
        val response = advice.handle(
            UpbitApiException(
                statusCode = 401,
                errorName = "no_authorization_ip",
                errorMessage = "This is not a verified IP.",
                rawBody = """{"error":{"name":"no_authorization_ip","message":"This is not a verified IP."}}""",
            )
        )

        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
        val message = response.body?.get("message") as String?
        assertNotNull(message)
        assertTrue(message!!.contains("허용 IP")) { "missing IP guidance: $message" }
    }

    @Test
    fun `other 401 is mapped to 400 not 401 to avoid frontend auto-logout`() {
        // FE api.js redirects to /login.html on any 401 — Upbit auth failures
        // must not look like our own session expiry.
        val response = advice.handle(
            UpbitApiException(401, "invalid_access_key", "invalid access key", "{}")
        )

        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
        val message = response.body?.get("message") as String?
        assertNotNull(message)
        assertTrue(message!!.contains("Upbit") && message.contains("인증")) {
            "should mention Upbit auth: $message"
        }
    }

    @Test
    fun `429 is mapped to 429 Too Many Requests`() {
        val response = advice.handle(UpbitApiException(429, null, "Too many requests", ""))

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, response.statusCode)
    }

    @Test
    fun `other 4xx is mapped to 400 with the error name surfaced`() {
        val response = advice.handle(UpbitApiException(403, "forbidden", "Forbidden", ""))

        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
        assertNotNull(response.body?.get("message"))
    }

    @Test
    fun `5xx is mapped to 502 Bad Gateway`() {
        val response = advice.handle(UpbitApiException(503, null, null, ""))

        assertEquals(HttpStatus.BAD_GATEWAY, response.statusCode)
    }

    @Test
    fun `unknown status code falls through to 502`() {
        val response = advice.handle(UpbitApiException(600, null, null, ""))

        assertEquals(HttpStatus.BAD_GATEWAY, response.statusCode)
    }
}
