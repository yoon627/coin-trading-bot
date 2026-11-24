package com.trading.bot.api

import com.trading.bot.client.UpbitClient
import com.trading.bot.domain.Account
import com.trading.bot.domain.Ticker
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
import org.junit.jupiter.api.Test
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.ReactiveSecurityContextHolder
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.server.WebFilter
import reactor.core.publisher.Mono

class PortfolioControllerExchangeTest {

    private val upbit = mockk<UserTradingManager>()
    private val paper = mockk<UserTradingManager>()
    private val userRepository = mockk<UserRepository>()
    private val userSecretsService = mockk<UserSecretsService>()
    private val upbitClient = mockk<UpbitClient>()
    private val user = UserEntity(id = 1L, username = "u", password = "p", upbitAccessKey = "enc-a", upbitSecretKey = "enc-s")

    init {
        every { userRepository.findById(1L) } returns Mono.just(user)
        every { userSecretsService.decryptUserSecrets(user) } returns user
        every { upbit.createUpbitClient(user) } returns upbitClient
        coEvery { upbitClient.getAccounts() } returns listOf(
            Account(currency = "KRW", balance = "1000"),
            Account(currency = "BTC", balance = "0.5", avgBuyPrice = "100"),
        )
        coEvery { upbitClient.getTicker("KRW-BTC") } returns listOf(Ticker(market = "KRW-BTC", tradePrice = 110.0))
    }

    private fun client(board: BoardPrices = BoardPrices { null }): WebTestClient = WebTestClient
        .bindToController(
            PortfolioController(
                TradingManagers.of(Exchange.UPBIT to upbit, Exchange.KIS_PAPER to paper),
                userRepository,
                userSecretsService,
                RequestValidators(),
                board,
            ),
        )
        .webFilter<WebTestClient.ControllerSpec>(
            WebFilter { exchange, chain ->
                chain.filter(exchange).contextWrite(
                    ReactiveSecurityContextHolder.withAuthentication(UsernamePasswordAuthenticationToken(1L, null, emptyList())),
                )
            },
        )
        .build()

    private fun WebTestClient.portfolio(query: String): WebTestClient.ResponseSpec = get().uri("/api/portfolio$query").exchange()

    @Test
    fun `파라미터 없음과 exchange=UPBIT 는 같은 Upbit 포트폴리오다`() {
        val client = client()

        val withoutParam = client.portfolio("").expectStatus().isOk
            .expectBody(String::class.java).returnResult().responseBody.orEmpty()
        val explicit = client.portfolio("?exchange=UPBIT").expectStatus().isOk
            .expectBody(String::class.java).returnResult().responseBody.orEmpty()

        assertEquals(withoutParam, explicit)
        client.portfolio("").expectStatus().isOk.expectBody()
            .jsonPath("$.krw_balance").isEqualTo(1000.0)
            .jsonPath("$.total_eval").isEqualTo(1055.0)
            .jsonPath("$.holdings[0].market").isEqualTo("KRW-BTC")
            .jsonPath("$.holdings[0].pnl_percent").isEqualTo(10.0)
        verify(exactly = 3) { upbit.createUpbitClient(user) }
    }

    @Test
    fun `UPBIT 가 아니면 등록돼 있어도 계좌에 닿기 전에 400 이다`() {
        val client = client()

        listOf("KIS_PAPER", "KIS", "upbit", "BINANCE").forEach {
            client.portfolio("?exchange=$it").expectStatus().isBadRequest
        }

        verify(exactly = 0) { userRepository.findById(any<Long>()) }
        verify(exactly = 0) { upbit.createUpbitClient(any()) }
        verify(exactly = 0) { paper.createUpbitClient(any()) }
        coVerify(exactly = 0) { upbitClient.getAccounts() }
    }

    // 시세판이 이미 든 시세는 사용자마다 업비트에 다시 묻지 않는다. 없는 코인만 한 번에 묻는다.
    @Test
    fun `board prices are used first and only the missing coins go to Upbit in one call`() {
        coEvery { upbitClient.getAccounts() } returns listOf(
            Account(currency = "KRW", balance = "1000"),
            Account(currency = "BTC", balance = "0.5", avgBuyPrice = "100"),
            Account(currency = "ETH", balance = "2", avgBuyPrice = "10"),
            Account(currency = "OLD", balance = "4", avgBuyPrice = "1"),
        )
        coEvery { upbitClient.getTicker("KRW-OLD") } returns listOf(Ticker(market = "KRW-OLD", tradePrice = 3.0))
        val board = BoardPrices { market -> mapOf("KRW-BTC" to 120.0, "KRW-ETH" to 11.0)[market] }

        client(board).portfolio("").expectStatus().isOk.expectBody()
            .jsonPath("$.holdings[0].current_price").isEqualTo(120.0)
            .jsonPath("$.holdings[1].current_price").isEqualTo(11.0)
            .jsonPath("$.holdings[2].current_price").isEqualTo(3.0)
            .jsonPath("$.total_eval").isEqualTo(1000.0 + 60.0 + 22.0 + 12.0)

        coVerify(exactly = 1) { upbitClient.getTicker("KRW-OLD") }
        coVerify(exactly = 1) { upbitClient.getTicker(any()) }
    }

    @Test
    fun `when the board has every held coin Upbit tickers are not called`() {
        client(BoardPrices { if (it == "KRW-BTC") 130.0 else null }).portfolio("").expectStatus().isOk.expectBody()
            .jsonPath("$.holdings[0].current_price").isEqualTo(130.0)

        coVerify(exactly = 0) { upbitClient.getTicker(any()) }
    }

    @Test
    fun `a failed fallback lookup still values those coins at zero`() {
        coEvery { upbitClient.getTicker(any()) } throws RuntimeException("429")

        client().portfolio("").expectStatus().isOk.expectBody()
            .jsonPath("$.holdings[0].current_price").isEqualTo(0.0)
            .jsonPath("$.total_eval").isEqualTo(1000.0)
    }
}
