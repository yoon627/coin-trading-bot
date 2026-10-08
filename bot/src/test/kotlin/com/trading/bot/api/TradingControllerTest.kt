package com.trading.bot.api

import com.trading.bot.engine.BotControlPersistFailedException
import com.trading.bot.engine.UserTradingManager
import com.trading.bot.persistence.UserRepository
import com.trading.bot.persistence.entity.UserEntity
import com.trading.bot.security.UserSecretsService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.reactor.asCoroutineContext
import kotlinx.coroutines.reactor.mono
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpStatus
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.ReactiveSecurityContextHolder
import org.springframework.web.server.ResponseStatusException
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks

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
        coEvery { manager.startBot(userId, null) } returns
            mapOf("error" to "Upbit API keys not configured. Set them via /api/user/keys")

        val ex = assertThrows<ResponseStatusException> {
            authed { controller.startBot(null) }
        }
        assertEquals(HttpStatus.BAD_REQUEST, ex.statusCode)
    }

    @Test
    fun `startBot surfaces user-not-found error as 404`() {
        coEvery { manager.startBot(userId, null) } returns
            mapOf("error" to "User not found")

        val ex = assertThrows<ResponseStatusException> {
            authed { controller.startBot(null) }
        }
        assertEquals(HttpStatus.NOT_FOUND, ex.statusCode)
    }

    @Test
    fun `startBot surfaces a running-engine ticker conflict as 409`() {
        coEvery { manager.startBot(userId, null) } returns
            mapOf("error" to "Bot is already running with [KRW-BTC] — stop it before changing tickers", "code" to "conflict")

        val ex = assertThrows<ResponseStatusException> {
            authed { controller.startBot(null) }
        }
        assertEquals(HttpStatus.CONFLICT, ex.statusCode)
    }

    @Test
    fun `startBot returns success map without throwing on happy path`() {
        coEvery { manager.startBot(userId, null) } returns
            mapOf("status" to "started", "strategy" to "combined")

        val result = authed { controller.startBot(null) }
        assertEquals("started", result["status"])
        assertEquals("combined", result["strategy"])
    }

    // --- 봇 제어의 상태 저장 실패는 503 (#228) — 엔드포인트마다 변환하므로 세 곳을 모두 본다 ---

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
        coEvery { manager.startBot(userId, null) } throws persistFailure
        assertServiceUnavailable { controller.startBot(null) }
    }

    @Test
    fun `clearHalt persistence failure is 503 with the manager's message`() {
        coEvery { manager.clearHalt(userId, "KRW-BTC") } throws persistFailure
        assertServiceUnavailable { controller.clearHalt(ClearHaltRequest("KRW-BTC")) }
    }

    // --- 설정 저장(Discord webhook) ---

    private val webhook = "https://discord.com/api/webhooks/1/token"

    private fun stubSettingsUser(storedWebhook: String?): MutableList<String?> {
        every { userRepo.findById(userId) } returns
            Mono.just(UserEntity(id = userId, username = "u", password = "p", discordWebhookUrl = storedWebhook))
        val saved = mutableListOf<String?>()
        every { userRepo.updateDiscordWebhookUrl(userId, any()) } answers { saved += secondArg<String?>(); Mono.just(1) }
        coEvery { manager.reloadUserRuntime(userId) } returns Unit
        return saved
    }

    @Test
    fun `settings stores the Discord webhook and reports it`() {
        val saved = stubSettingsUser(storedWebhook = null)

        val res = authed { controller.updateSettings(UserSettingsRequest(discordWebhookUrl = "  $webhook ")) }

        assertEquals(listOf<String?>(webhook), saved)
        assertEquals(true, res["has_discord_webhook"])
        coVerify(exactly = 1) { manager.reloadUserRuntime(userId) }
    }

    @Test
    fun `settings without the webhook field changes nothing and does not restart the engine`() {
        // 필드가 없는 요청이 저장된 webhook 을 지우면 알림이 조용히 끊기고, 저장하면 쓸데없이 엔진을 재기동한다.
        stubSettingsUser(storedWebhook = webhook)

        val res = authed { controller.updateSettings(UserSettingsRequest(discordWebhookUrl = null)) }

        assertEquals(true, res["has_discord_webhook"])
        verify(exactly = 0) { userRepo.updateDiscordWebhookUrl(any(), any()) }
        coVerify(exactly = 0) { manager.reloadUserRuntime(any()) }
    }

    @Test
    fun `settings answers 401 when the session's user no longer exists`() {
        every { userRepo.findById(userId) } returns Mono.empty()

        val ex = assertThrows<ResponseStatusException> {
            authed { controller.updateSettings(UserSettingsRequest(discordWebhookUrl = webhook)) }
        }
        assertEquals(HttpStatus.UNAUTHORIZED, ex.statusCode)
        verify(exactly = 0) { userRepo.updateDiscordWebhookUrl(any(), any()) }
    }

    @Test
    fun `settings clears the webhook on an empty string`() {
        val saved = stubSettingsUser(storedWebhook = webhook)

        val res = authed { controller.updateSettings(UserSettingsRequest(discordWebhookUrl = "")) }

        assertEquals(listOf<String?>(null), saved)
        assertEquals(false, res["has_discord_webhook"])
    }

    @Test
    fun `a save that outlasts the limit still reloads and reports the result as unconfirmed`() = runTest {
        // 상한을 넘긴 저장은 커밋됐는지 모른다 — reload 는 DB 를 다시 읽을 뿐이라 그래도 부르고, 저장 실패(500)가 아니라 확인 불가로 알린다.
        every { userRepo.findById(userId) } returns Mono.just(UserEntity(id = userId, username = "u", password = "p"))
        every { userRepo.updateDiscordWebhookUrl(userId, webhook) } returns Mono.never()
        coEvery { manager.reloadUserRuntime(userId) } returns Unit

        val ex = runCatching {
            withContext(authContext.asCoroutineContext()) { controller.updateSettings(UserSettingsRequest(discordWebhookUrl = webhook)) }
        }.exceptionOrNull() as ResponseStatusException

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ex.statusCode)
        assertEquals(TradingController.SETTINGS_SAVE_UNCONFIRMED_MESSAGE, ex.reason)
        coVerify(exactly = 1) { manager.reloadUserRuntime(userId) }
    }

    @Test
    fun `a request cut off while the setting is being saved still finishes the save and reloads`() {
        // 저장이 커밋된 뒤 끊겨 reload 가 불리지 않으면 DB 와 도는 엔진이 조용히 갈린다(#283). 끝까지 가면 끊긴 reload 가 미반영을 알린다.
        every { userRepo.findById(userId) } returns Mono.just(UserEntity(id = userId, username = "u", password = "p"))
        val saving = Sinks.one<Int>()
        every { userRepo.updateDiscordWebhookUrl(userId, webhook) } returns saving.asMono()
        coEvery { manager.reloadUserRuntime(userId) } returns Unit

        val request = mono { controller.updateSettings(UserSettingsRequest(discordWebhookUrl = webhook)) }
            .contextWrite(authContext).subscribe({}, {})
        verify(timeout = 2_000) { userRepo.updateDiscordWebhookUrl(userId, webhook) }
        request.dispose()
        saving.tryEmitValue(1)

        coVerify(timeout = 2_000) { manager.reloadUserRuntime(userId) }
    }
}
