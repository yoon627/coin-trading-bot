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

    private fun client(): WebTestClient = WebTestClient
        .bindToController(
            PortfolioController(
                TradingManagers.of(Exchange.UPBIT to upbit, Exchange.KIS_PAPER to paper),
                userRepository,
                userSecretsService,
                RequestValidators(),
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
}
