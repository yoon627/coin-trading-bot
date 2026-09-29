package com.trading.bot.api

import com.trading.bot.engine.BotControlPersistFailedException
import com.trading.bot.engine.UserTradingManager
import com.trading.bot.persistence.UserRepository
import com.trading.bot.persistence.entity.UserEntity
import com.trading.bot.security.UserSecretsService
import io.mockk.CapturingSlot
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.reactor.mono
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpStatus
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.ReactiveSecurityContextHolder
import org.springframework.web.server.ResponseStatusException
import reactor.core.publisher.Mono

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
    fun `startBot surfaces a running-engine ticker conflict as 409`() {
        coEvery { manager.startBot(userId, null, null) } returns
            mapOf("error" to "Bot is already running with [KRW-BTC] — stop it before changing tickers", "code" to "conflict")

        val ex = assertThrows<ResponseStatusException> {
            authed { controller.startBot(null) }
        }
        assertEquals(HttpStatus.CONFLICT, ex.statusCode)
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

    // --- 봇 제어의 상태 저장 실패는 503 (#228) — 엔드포인트마다 변환하므로 네 곳을 모두 본다 ---

    private val persistFailure = BotControlPersistFailedException("저장 실패 안내", RuntimeException("db down"))

    private fun assertServiceUnavailable(block: suspend () -> Any) {
        val ex = assertThrows<ResponseStatusException> { authed { block() } }
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ex.statusCode)
        assertEquals("저장 실패 안내", ex.reason)
    }

    @Test
    fun `stopBot persistence failure is 503 with the manager's message`() {
        coEvery { manager.stopBot(userId) } throws persistFailure
        assertServiceUnavailable { controller.stopBot() }
    }

    @Test
    fun `startBot persistence failure is 503 with the manager's message`() {
        coEvery { manager.startBot(userId, null, null) } throws persistFailure
        assertServiceUnavailable { controller.startBot(null) }
    }

    @Test
    fun `changeStrategy persistence failure is 503 with the manager's message`() {
        coEvery { manager.setStrategy(userId, "combined") } throws persistFailure
        assertServiceUnavailable { controller.changeStrategy(StrategyRequest("combined")) }
    }

    @Test
    fun `clearHalt persistence failure is 503 with the manager's message`() {
        coEvery { manager.clearHalt(userId, "KRW-BTC") } throws persistFailure
        assertServiceUnavailable { controller.clearHalt(ClearHaltRequest("KRW-BTC")) }
    }

    @Test
    fun `startBot maps an unknown strategy to 400 even when the name says not found`() {
        coEvery { manager.startBot(userId, null, "x not found") } returns
            mapOf("error" to "Unknown strategy: x not found", "code" to UserTradingManager.UNKNOWN_STRATEGY_CODE)

        val ex = assertThrows<ResponseStatusException> {
            authed { controller.startBot(StartBotRequest(strategy = "x not found")) }
        }
        assertEquals(HttpStatus.BAD_REQUEST, ex.statusCode)
    }

    // --- 설정 저장(Discord webhook) ---

    private val webhook = "https://discord.com/api/webhooks/1/token"

    private fun stubSettingsUser(storedWebhook: String?): CapturingSlot<UserEntity> {
        every { userRepo.findById(userId) } returns
            Mono.just(UserEntity(id = userId, username = "u", password = "p", discordWebhookUrl = storedWebhook))
        val saved = slot<UserEntity>()
        every { userRepo.save(capture(saved)) } answers { Mono.just(saved.captured) }
        coEvery { manager.reloadUserRuntime(userId) } returns Unit
        return saved
    }

    @Test
    fun `settings stores the Discord webhook and reports it`() {
        val saved = stubSettingsUser(storedWebhook = null)

        val res = authed { controller.updateSettings(UserSettingsRequest(discordWebhookUrl = "  $webhook ")) }

        assertEquals(webhook, saved.captured.discordWebhookUrl)
        assertEquals(true, res["has_discord_webhook"])
        coVerify(exactly = 1) { manager.reloadUserRuntime(userId) }
    }

    @Test
    fun `settings without the webhook field changes nothing and does not restart the engine`() {
        // 필드가 없는 요청이 저장된 webhook 을 지우면 알림이 조용히 끊기고, 저장하면 쓸데없이 엔진을 재기동한다.
        stubSettingsUser(storedWebhook = webhook)

        val res = authed { controller.updateSettings(UserSettingsRequest(discordWebhookUrl = null)) }

        assertEquals(true, res["has_discord_webhook"])
        verify(exactly = 0) { userRepo.save(any()) }
        coVerify(exactly = 0) { manager.reloadUserRuntime(any()) }
    }

    @Test
    fun `settings answers 401 when the session's user no longer exists`() {
        every { userRepo.findById(userId) } returns Mono.empty()

        val ex = assertThrows<ResponseStatusException> {
            authed { controller.updateSettings(UserSettingsRequest(discordWebhookUrl = webhook)) }
        }
        assertEquals(HttpStatus.UNAUTHORIZED, ex.statusCode)
        verify(exactly = 0) { userRepo.save(any()) }
    }

    @Test
    fun `settings clears the webhook on an empty string`() {
        val saved = stubSettingsUser(storedWebhook = webhook)

        val res = authed { controller.updateSettings(UserSettingsRequest(discordWebhookUrl = "")) }

        assertNull(saved.captured.discordWebhookUrl)
        assertEquals(false, res["has_discord_webhook"])
    }
}
