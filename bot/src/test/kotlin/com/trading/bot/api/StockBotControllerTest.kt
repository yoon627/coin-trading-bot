package com.trading.bot.api

import com.trading.bot.kis.client.KisClientFactory
import com.trading.bot.kis.engine.StockUserTradingManager
import com.trading.bot.persistence.UserRepository
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

class StockBotControllerTest {
    private val userId = 42L
    private val authContext = ReactiveSecurityContextHolder.withAuthentication(
        UsernamePasswordAuthenticationToken(userId, null, emptyList())
    )

    private val manager = mockk<StockUserTradingManager>()
    private val userRepo = mockk<UserRepository>()
    private val kisClientFactory = mockk<KisClientFactory>()
    private val validators = RequestValidators()
    private val controller = StockBotController(manager, userRepo, kisClientFactory, validators)

    private fun <T : Any> authed(block: suspend () -> T): T =
        mono { block() }.contextWrite(authContext).block()!!

    @Test
    fun `start validates symbols and returns status`() {
        coEvery { manager.startBot(userId, listOf("005930"), "rsi") } returns
            mapOf("status" to "started", "strategy" to "rsi", "live" to false)

        val result = authed { controller.start(StartStockBotRequest(symbols = listOf("005930"), strategy = "rsi")) }
        assertEquals("started", result["status"])
    }

    @Test
    fun `start rejects non-6-digit symbol`() {
        val ex = assertThrows<ResponseStatusException> {
            authed { controller.start(StartStockBotRequest(symbols = listOf("AAPL"))) }
        }
        assertEquals(HttpStatus.BAD_REQUEST, ex.statusCode)
    }

    @Test
    fun `start rejects empty symbols`() {
        val ex = assertThrows<ResponseStatusException> {
            authed { controller.start(StartStockBotRequest(symbols = emptyList())) }
        }
        assertEquals(HttpStatus.BAD_REQUEST, ex.statusCode)
    }

    @Test
    fun `start surfaces manager error as 400`() {
        coEvery { manager.startBot(userId, listOf("005930"), null) } returns
            mapOf("error" to "KIS API keys not configured. Set them via /api/user/kis-keys")

        val ex = assertThrows<ResponseStatusException> {
            authed { controller.start(StartStockBotRequest(symbols = listOf("005930"))) }
        }
        assertEquals(HttpStatus.BAD_REQUEST, ex.statusCode)
    }

    @Test
    fun `strategy rejects unknown`() {
        coEvery { manager.setStrategy(userId, "nope") } returns false
        val ex = assertThrows<ResponseStatusException> {
            authed { controller.strategy(StrategyRequest("nope")) }
        }
        assertEquals(HttpStatus.BAD_REQUEST, ex.statusCode)
    }
}
