package com.trading.bot.api

import com.trading.bot.engine.BotControlPersistFailedException
import com.trading.bot.engine.TradingManagers
import com.trading.bot.engine.BotChangeFailedException
import com.trading.bot.engine.UserTradingManager
import com.trading.bot.persistence.UserRepository
import com.trading.bot.persistence.entity.UserEntity
import com.trading.bot.security.UserSecretsService
import com.trading.common.domain.Exchange
import com.trading.bot.marketdata.MarketCatalog
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
    private val secrets = mockk<UserSecretsService> { every { encryptWebhook(any()) } answers { firstArg<String?>()?.let { "enc:$it" } } }
    private val catalog = mockk<MarketCatalog> { coEvery { unknown(any()) } returns emptyList() }
    private val controller = TradingController(TradingManagers.of(Exchange.UPBIT to manager), userRepo, validators, secrets, catalog)

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

    // 업비트에 없는 마켓은 봇이 시세를 받지 못해 조용히 아무것도 하지 않는다 — 시작 전에 거절한다.
    @Test
    fun `startBot refuses markets that Upbit does not list`() {
        coEvery { catalog.unknown(listOf("KRW-BTC", "KRW-NOPE")) } returns listOf("KRW-NOPE")

        val ex = assertThrows<ResponseStatusException> {
            authed { controller.startBot(StartBotRequest(tickers = listOf("KRW-BTC", "KRW-NOPE"))) }
        }
        assertEquals(HttpStatus.BAD_REQUEST, ex.statusCode)
        assertEquals("Unknown markets: KRW-NOPE", ex.reason)
        coVerify(exactly = 0) { manager.startBot(any(), any()) }
    }

    // 목록을 못 받았으면(업비트 장애) 형식 검사만으로 시작한다.
    @Test
    fun `startBot accepts well-formed markets when the market list is unavailable`() {
        coEvery { catalog.unknown(any()) } returns null
        coEvery { manager.startBot(userId, listOf("KRW-BTC")) } returns mapOf("status" to "started")

        assertEquals("started", authed { controller.startBot(StartBotRequest(tickers = listOf("KRW-BTC"))) }["status"])
    }

    // 도는 봇의 목록 교체가 실패해 옛 엔진·옛 목록으로 돌아가면 503 과 목록 문구다 — 키 저장 문구("저장은 완료")는 사실과 다르다.
    @Test
    fun `startBot surfaces a failed ticker change as 503 with the ticker message`() {
        coEvery { manager.startBot(userId, listOf("KRW-BTC")) } throws
            BotChangeFailedException(userId, UserTradingManager.TICKERS_NOT_CHANGED_MESSAGE, RuntimeException("db down"))

        val ex = assertThrows<ResponseStatusException> {
            authed { controller.startBot(StartBotRequest(tickers = listOf("KRW-BTC"))) }
        }
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ex.statusCode)
        assertEquals(UserTradingManager.TICKERS_NOT_CHANGED_MESSAGE, ex.reason)
    }

    @Test
    fun `tickers returns the manager's saved list`() {
        coEvery { manager.savedTickers(userId) } returns listOf("KRW-ETH")

        assertEquals(mapOf("tickers" to listOf("KRW-ETH")), authed { controller.tickers() })
    }

    // 화면이 고칠 때마다 저장한다 — 시작 요청과 같은 형식·업비트 목록 검증을 거친다.
    @Test
    fun `saveTickers validates the list and hands it to the manager`() {
        coEvery { manager.saveTickers(userId, listOf("KRW-ETH")) } returns mapOf("status" to "saved")
        coEvery { catalog.unknown(listOf("KRW-NOPE")) } returns listOf("KRW-NOPE")

        assertEquals("saved", authed { controller.saveTickers(StartBotRequest(tickers = listOf("krw-eth"))) }["status"])
        val unknown = assertThrows<ResponseStatusException> { authed { controller.saveTickers(StartBotRequest(tickers = listOf("KRW-NOPE"))) } }
        val empty = assertThrows<ResponseStatusException> { authed { controller.saveTickers(StartBotRequest(tickers = emptyList())) } }
        assertEquals(HttpStatus.BAD_REQUEST, unknown.statusCode)
        assertEquals(HttpStatus.BAD_REQUEST, empty.statusCode)
    }

    @Test
    fun `startBot returns success map without throwing on happy path`() {
        coEvery { manager.startBot(userId, null) } returns
            mapOf("status" to "started", "strategy" to "combined")

        val result = authed { controller.startBot(null) }
        assertEquals("started", result["status"])
        assertEquals("combined", result["strategy"])
    }

    // 봇 제어의 상태 저장 실패는 503 이다. 엔드포인트마다 변환하므로 세 곳을 모두 본다.

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

    // 설정 저장(Discord webhook)

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

        assertEquals(listOf<String?>("enc:$webhook"), saved)
        assertEquals(mapOf("has_discord_webhook" to true), res)
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
        // 상한을 넘긴 저장은 커밋 여부를 모른다. reload 는 DB 를 다시 읽을 뿐이라 그래도 부르고, 500 이 아니라 확인 불가로 알린다.
        every { userRepo.findById(userId) } returns Mono.just(UserEntity(id = userId, username = "u", password = "p"))
        every { userRepo.updateDiscordWebhookUrl(userId, "enc:$webhook") } returns Mono.never()
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
        // 커밋 뒤 요청이 끊겨 reload 가 빠지면 DB 와 실행 중인 엔진이 갈린다.
        every { userRepo.findById(userId) } returns Mono.just(UserEntity(id = userId, username = "u", password = "p"))
        val saving = Sinks.one<Int>()
        every { userRepo.updateDiscordWebhookUrl(userId, "enc:$webhook") } returns saving.asMono()
        coEvery { manager.reloadUserRuntime(userId) } returns Unit

        val request = mono { controller.updateSettings(UserSettingsRequest(discordWebhookUrl = webhook)) }
            .contextWrite(authContext).subscribe({}, {})
        verify(timeout = 2_000) { userRepo.updateDiscordWebhookUrl(userId, "enc:$webhook") }
        request.dispose()
        saving.tryEmitValue(1)

        coVerify(timeout = 2_000) { manager.reloadUserRuntime(userId) }
    }
}
