package com.trading.bot.api

import com.trading.bot.client.UpbitClient
import com.trading.bot.domain.Account
import com.trading.bot.domain.TradeSide
import com.trading.bot.engine.RuntimeReloadFailedException
import com.trading.bot.engine.TradingManagers
import com.trading.bot.engine.UserTradingManager
import com.trading.bot.persistence.UserRepository
import com.trading.bot.persistence.entity.UserEntity
import com.trading.bot.security.UserSecretsService
import com.trading.common.domain.Exchange
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.ReactiveSecurityContextHolder
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.server.WebFilter
import reactor.core.publisher.Mono

/** 파라미터 해석은 실제 WebFlux 바인딩을 거쳐야 정해지므로 HTTP 요청으로 본다. */
class TradingControllerExchangeTest {

    private val upbit = mockk<UserTradingManager>(relaxed = true)
    private val paper = mockk<UserTradingManager>(relaxed = true)
    private val userRepository = mockk<UserRepository>()
    private val userSecretsService = mockk<UserSecretsService>()

    private val status = mapOf("running" to true, "tickers" to listOf("KRW-BTC"))

    init {
        coEvery { upbit.getStatus(1L) } returns status
        coEvery { upbit.startBot(1L, any()) } returns mapOf("status" to "started")
        coEvery { upbit.stopBot(1L) } returns mapOf("status" to "stopped")
        coEvery { upbit.clearHalt(1L, any()) } returns mapOf("status" to "cleared")
        coEvery { upbit.clearPending(1L, any(), any()) } returns mapOf("status" to "cleared")
    }

    private fun authFilter(): WebFilter = WebFilter { exchange, chain ->
        chain.filter(exchange).contextWrite(
            ReactiveSecurityContextHolder.withAuthentication(UsernamePasswordAuthenticationToken(1L, null, emptyList())),
        )
    }

    private fun client(managers: TradingManagers = TradingManagers.of(Exchange.UPBIT to upbit)): WebTestClient = WebTestClient
        .bindToController(TradingController(managers, userRepository, RequestValidators(), userSecretsService))
        .webFilter<WebTestClient.ControllerSpec>(authFilter())
        .build()

    private fun WebTestClient.post(path: String, body: Any? = null): WebTestClient.ResponseSpec =
        post().uri(path).contentType(MediaType.APPLICATION_JSON)
            .let { if (body == null) it else it.bodyValue(body) }
            .exchange()

    private fun suffix(exchange: String?): String = exchange?.let { "?exchange=$it" }.orEmpty()

    private fun WebTestClient.ResponseSpec.bodyText(): String =
        expectBody(String::class.java).returnResult().responseBody.orEmpty()

    private fun controlRequests(exchange: String?): List<WebTestClient.ResponseSpec> {
        val client = client()
        val q = suffix(exchange)
        return listOf(
            client.get().uri("/api/bot/status$q").exchange(),
            client.post("/api/bot/start$q", mapOf("tickers" to listOf("krw-btc"))),
            client.post("/api/bot/start$q"),
            client.post("/api/bot/stop$q"),
            client.post("/api/bot/halt/clear$q", mapOf("ticker" to "krw-btc")),
            client.post("/api/bot/pending/clear$q", mapOf("ticker" to "krw-btc", "side" to "sell")),
        )
    }

    @Test
    fun `파라미터 없음과 exchange=UPBIT 는 같은 응답과 같은 매니저 호출이다`() {
        val withoutParam = controlRequests(null).map { it.expectStatus().isOk; it.bodyText() }
        val explicit = controlRequests("UPBIT").map { it.expectStatus().isOk; it.bodyText() }
        val empty = controlRequests("").map { it.expectStatus().isOk; it.bodyText() }

        assertEquals(withoutParam, explicit)
        assertEquals(withoutParam, empty)
        assertTrue(withoutParam.first().contains("\"running\":true"), withoutParam.first())
        coVerify(exactly = 3) { upbit.getStatus(1L) }
        coVerify(exactly = 3) { upbit.startBot(1L, listOf("KRW-BTC")) }
        coVerify(exactly = 3) { upbit.startBot(1L, null) }
        coVerify(exactly = 3) { upbit.stopBot(1L) }
        coVerify(exactly = 3) { upbit.clearHalt(1L, "KRW-BTC") }
        coVerify(exactly = 3) { upbit.clearPending(1L, "KRW-BTC", TradeSide.SELL) }
    }

    @Test
    fun `등록되지 않은 거래소·모르는 값은 매니저에 닿기 전에 400 이다`() {
        listOf("KIS_PAPER", "KIS", "upbit", "BINANCE").forEach { exchange ->
            controlRequests(exchange).forEach { it.expectStatus().isBadRequest }
        }
        coVerify(exactly = 0) { upbit.getStatus(any()) }
        coVerify(exactly = 0) { upbit.startBot(any(), any()) }
        coVerify(exactly = 0) { upbit.stopBot(any()) }
        coVerify(exactly = 0) { upbit.clearHalt(any(), any()) }
        coVerify(exactly = 0) { upbit.clearPending(any(), any(), any()) }
    }

    @Test
    fun `등록된 KIS 매니저로 가고 티커는 KRX 규칙으로 본다`() {
        coEvery { paper.startBot(1L, any()) } returns mapOf("status" to "started")
        coEvery { paper.clearHalt(1L, any()) } returns mapOf("status" to "cleared")
        coEvery { paper.clearPending(1L, any(), any()) } returns mapOf("status" to "cleared")
        val client = client(TradingManagers.of(Exchange.UPBIT to upbit, Exchange.KIS_PAPER to paper))

        client.post("/api/bot/start?exchange=KIS_PAPER", mapOf("tickers" to listOf("krx-005930"))).expectStatus().isOk
        client.post("/api/bot/halt/clear?exchange=KIS_PAPER", mapOf("ticker" to "KRX-005930")).expectStatus().isOk
        client.post("/api/bot/pending/clear?exchange=KIS_PAPER", mapOf("ticker" to "KRX-005930", "side" to "buy"))
            .expectStatus().isOk
        client.post("/api/bot/start?exchange=KIS_PAPER", mapOf("tickers" to listOf("KRW-BTC"))).expectStatus().isBadRequest
        client.post("/api/bot/halt/clear?exchange=KIS_PAPER", mapOf("ticker" to "KRW-BTC")).expectStatus().isBadRequest
        client.post("/api/bot/pending/clear?exchange=KIS_PAPER", mapOf("ticker" to "KRW-BTC", "side" to "buy"))
            .expectStatus().isBadRequest

        coVerify(exactly = 1) { paper.startBot(1L, listOf("KRX-005930")) }
        coVerify(exactly = 1) { paper.clearHalt(1L, "KRX-005930") }
        coVerify(exactly = 1) { paper.clearPending(1L, "KRX-005930", TradeSide.BUY) }
        coVerify(exactly = 0) { upbit.startBot(any(), any()) }
        coVerify(exactly = 0) { upbit.clearHalt(any(), any()) }
        coVerify(exactly = 0) { upbit.clearPending(any(), any(), any()) }
    }

    @Test
    fun `계좌 조회는 UPBIT 만 받고 파라미터 없음과 UPBIT 명시가 같다`() {
        val user = UserEntity(id = 1L, username = "u", password = "p", upbitAccessKey = "enc-a", upbitSecretKey = "enc-s")
        val upbitClient = mockk<UpbitClient>()
        every { userRepository.findById(1L) } returns Mono.just(user)
        every { userSecretsService.decryptUserSecrets(user) } returns user
        every { upbit.createUpbitClient(user) } returns upbitClient
        coEvery { upbitClient.getAccounts() } returns
            listOf(Account(currency = "KRW", balance = "1000"))
        val client = client(TradingManagers.of(Exchange.UPBIT to upbit, Exchange.KIS_PAPER to paper))

        val withoutParam = client.get().uri("/api/account").exchange().expectStatus().isOk.let { it.bodyText() }
        val explicit = client.get().uri("/api/account?exchange=UPBIT").exchange().expectStatus().isOk.let { it.bodyText() }
        listOf("KIS_PAPER", "KIS", "upbit").forEach {
            client.get().uri("/api/account?exchange=$it").exchange().expectStatus().isBadRequest
        }

        assertEquals(withoutParam, explicit)
        assertTrue(withoutParam.contains("\"currency\":\"KRW\""), withoutParam)
        verify(exactly = 2) { upbit.createUpbitClient(user) }
        verify(exactly = 0) { paper.createUpbitClient(any()) }
    }

    @Test
    fun `설정·키 저장은 등록된 모든 매니저를 다시 읽히고 한쪽 실패는 503 이다`() {
        every { userRepository.findById(1L) } returns Mono.just(UserEntity(id = 1L, username = "u", password = "p"))
        every { userRepository.updateDiscordWebhookUrl(1L, any()) } returns Mono.just(1)
        every { userRepository.updateUpbitKeys(1L, any(), any()) } returns Mono.just(1)
        every { userSecretsService.encryptUpbitKeys(any(), any()) } returns ("enc-a" to "enc-s")
        val client = client(TradingManagers.of(Exchange.UPBIT to upbit, Exchange.KIS_PAPER to paper))

        client.post("/api/user/settings", mapOf("discordWebhookUrl" to "https://discord.com/api/webhooks/1/token"))
            .expectStatus().isOk
        client.post("/api/user/keys", mapOf("accessKey" to "A".repeat(32), "secretKey" to "B".repeat(32)))
            .expectStatus().isOk
        coVerify(exactly = 2) { upbit.reloadUserRuntime(1L) }
        coVerify(exactly = 2) { paper.reloadUserRuntime(1L) }

        coEvery { upbit.reloadUserRuntime(1L) } throws
            RuntimeReloadFailedException(1L, RuntimeException("db down"), engineRestored = true)
        client.post("/api/user/settings", mapOf("discordWebhookUrl" to "https://discord.com/api/webhooks/1/token"))
            .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
        coVerify(exactly = 3) { paper.reloadUserRuntime(1L) }
    }
}
