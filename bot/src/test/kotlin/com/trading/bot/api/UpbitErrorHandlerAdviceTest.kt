package com.trading.bot.api

import com.trading.bot.client.UpbitApiException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException

class UpbitErrorHandlerAdviceTest {

    private val advice = UpbitErrorHandlerAdvice()

    @Test
    fun `401 no_authorization_ip is mapped to 400 with IP guidance reason`() {
        val ex = UpbitApiException(
            statusCode = 401,
            errorName = "no_authorization_ip",
            errorMessage = "This is not a verified IP.",
            rawBody = """{"error":{"name":"no_authorization_ip","message":"This is not a verified IP."}}""",
        )

        val rse = assertThrows<ResponseStatusException> { advice.handle(ex) }

        assertEquals(HttpStatus.BAD_REQUEST, rse.statusCode)
        assertNotNull(rse.reason)
        assertTrue(rse.reason!!.contains("허용 IP")) { "reason missing IP guidance: ${rse.reason}" }
    }

    @Test
    fun `other 401 is mapped to 400 not 401 to avoid frontend auto-logout`() {
        // FE api.js redirects to /login.html on any 401 — Upbit auth failures
        // must not look like our own session expiry.
        val ex = UpbitApiException(
            statusCode = 401,
            errorName = "invalid_access_key",
            errorMessage = "invalid access key",
            rawBody = """{"error":{"name":"invalid_access_key","message":"invalid access key"}}""",
        )

        val rse = assertThrows<ResponseStatusException> { advice.handle(ex) }

        assertEquals(HttpStatus.BAD_REQUEST, rse.statusCode)
        assertNotNull(rse.reason)
        assertTrue(rse.reason!!.contains("Upbit") && rse.reason!!.contains("인증")) {
            "reason should mention Upbit auth: ${rse.reason}"
        }
    }

    @Test
    fun `429 is mapped to 429 Too Many Requests`() {
        val ex = UpbitApiException(
            statusCode = 429,
            errorName = null,
            errorMessage = "Too many requests",
            rawBody = "",
        )

        val rse = assertThrows<ResponseStatusException> { advice.handle(ex) }

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, rse.statusCode)
    }

    @Test
    fun `other 4xx is mapped to 400 with the error name surfaced`() {
        val ex = UpbitApiException(
            statusCode = 403,
            errorName = "forbidden",
            errorMessage = "Forbidden",
            rawBody = "",
        )

        val rse = assertThrows<ResponseStatusException> { advice.handle(ex) }

        assertEquals(HttpStatus.BAD_REQUEST, rse.statusCode)
        assertNotNull(rse.reason)
    }

    @Test
    fun `5xx is mapped to 502 Bad Gateway`() {
        val ex = UpbitApiException(
            statusCode = 503,
            errorName = null,
            errorMessage = null,
            rawBody = "",
        )

        val rse = assertThrows<ResponseStatusException> { advice.handle(ex) }

        assertEquals(HttpStatus.BAD_GATEWAY, rse.statusCode)
    }

    @Test
    fun `unknown status code falls through to 502`() {
        val ex = UpbitApiException(
            statusCode = 600,
            errorName = null,
            errorMessage = null,
            rawBody = "",
        )

        val rse = assertThrows<ResponseStatusException> { advice.handle(ex) }

        assertEquals(HttpStatus.BAD_GATEWAY, rse.statusCode)
    }
}
