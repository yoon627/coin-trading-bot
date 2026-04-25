package com.trading.bot.api

import com.trading.bot.engine.UserTradingManager
import com.trading.bot.persistence.UserRepository
import com.trading.bot.security.UserSecretsService
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.reactor.mono
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpStatus
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.ReactiveSecurityContextHolder
import org.springframework.web.server.ResponseStatusException

class TradingControllerTest {

    private val userId = 42L
    private val authContext = ReactiveSecurityContextHolder.withAuthentication(
        UsernamePasswordAuthenticationToken(userId, null, emptyList())
    )

    private val manager = mockk<UserTradingManager>()
    private val userRepo = mockk<UserRepository>()
    private val validators = RequestValidators()
    private val secrets = mockk<UserSecretsService>()
    private val controller = TradingController(manager, userRepo, validators, secrets)

    private fun <T : Any> authed(block: suspend () -> T): T =
        mono { block() }.contextWrite(authContext).block()!!

    @Test
    fun `startBot surfaces missing-keys error as 400 instead of 200`() {
        coEvery { manager.startBot(userId, null, null) } returns
            mapOf("error" to "Upbit API keys not configured. Set them via /api/user/keys")

        val ex = assertThrows<ResponseStatusException> {
            authed { controller.startBot(null) }
        }
        assertEquals(HttpStatus.BAD_REQUEST, ex.statusCode)
    }

    @Test
    fun `startBot surfaces user-not-found error as 404`() {
        coEvery { manager.startBot(userId, null, null) } returns
            mapOf("error" to "User not found")

        val ex = assertThrows<ResponseStatusException> {
            authed { controller.startBot(null) }
        }
        assertEquals(HttpStatus.NOT_FOUND, ex.statusCode)
    }

    @Test
    fun `startBot returns success map without throwing on happy path`() {
        coEvery { manager.startBot(userId, null, null) } returns
            mapOf("status" to "started", "strategy" to "volatility_breakout")

        val result = authed { controller.startBot(null) }
        assertEquals("started", result["status"])
        assertEquals("volatility_breakout", result["strategy"])
    }

    @Test
    fun `changeStrategy throws 400 on unknown strategy instead of 200 with error body`() {
        coEvery { manager.setStrategy(userId, "nonexistent") } returns false

        val ex = assertThrows<ResponseStatusException> {
            authed { controller.changeStrategy(StrategyRequest("nonexistent")) }
        }
        assertEquals(HttpStatus.BAD_REQUEST, ex.statusCode)
    }

    @Test
    fun `changeStrategy returns success map on valid strategy`() {
        coEvery { manager.setStrategy(userId, "volatility_breakout") } returns true

        val result = authed { controller.changeStrategy(StrategyRequest("volatility_breakout")) }
        assertEquals("changed", result["status"])
        assertEquals("volatility_breakout", result["strategy"])
    }
}
