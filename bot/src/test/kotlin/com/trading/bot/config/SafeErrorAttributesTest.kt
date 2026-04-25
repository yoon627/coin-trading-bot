package com.trading.bot.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.web.error.ErrorAttributeOptions
import org.springframework.http.HttpStatus
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.web.reactive.function.server.HandlerStrategies
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.server.ResponseStatusException

class SafeErrorAttributesTest {

    private val attrs = SafeErrorAttributes()

    private fun serverRequestWithError(error: Throwable): ServerRequest {
        val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/test"))
        attrs.storeErrorInformation(error, exchange)
        return ServerRequest.create(exchange, HandlerStrategies.withDefaults().messageReaders())
    }

    @Test
    fun `ResponseStatusException reason is exposed as message`() {
        val request = serverRequestWithError(
            ResponseStatusException(HttpStatus.BAD_REQUEST, "Password must be at least 8 characters")
        )

        val result = attrs.getErrorAttributes(request, ErrorAttributeOptions.defaults())

        assertEquals("Password must be at least 8 characters", result["message"])
        assertEquals(400, result["status"])
    }

    @Test
    fun `arbitrary exception message is suppressed`() {
        val request = serverRequestWithError(
            RuntimeException("Database connection failed at localhost:5432")
        )

        val result = attrs.getErrorAttributes(request, ErrorAttributeOptions.defaults())

        assertFalse(result.containsKey("message")) { "message should be hidden, got: ${result["message"]}" }
        assertFalse(result.containsKey("errors"))
        assertFalse(result.containsKey("trace"))
    }

    @Test
    fun `IllegalArgumentException message is suppressed`() {
        val request = serverRequestWithError(
            IllegalArgumentException("Internal state inconsistency in TradingEngine#42")
        )

        val result = attrs.getErrorAttributes(request, ErrorAttributeOptions.defaults())

        assertFalse(result.containsKey("message"))
    }

    @Test
    fun `ResponseStatusException without reason results in no message field`() {
        val request = serverRequestWithError(ResponseStatusException(HttpStatus.NOT_FOUND))

        val result = attrs.getErrorAttributes(request, ErrorAttributeOptions.defaults())

        assertFalse(result.containsKey("message"))
        assertEquals(404, result["status"])
    }

    @Test
    fun `even with INCLUDE_MESSAGE option, non-RSE messages are still stripped`() {
        val request = serverRequestWithError(
            RuntimeException("Internal: SQL syntax error near 'DROP TABLE'")
        )

        val result = attrs.getErrorAttributes(
            request,
            ErrorAttributeOptions.defaults().including(ErrorAttributeOptions.Include.MESSAGE)
        )

        // Even when MESSAGE is included by config, our override removes it for non-RSE
        assertFalse(result.containsKey("message"))
        assertTrue(result.containsKey("status"))
    }
}
