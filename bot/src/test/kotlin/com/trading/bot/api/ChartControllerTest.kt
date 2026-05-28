package com.trading.bot.api

import com.trading.bot.marketdata.MarketDataStore
import com.trading.bot.persistence.MarketCandleRepository
import com.trading.common.domain.Exchange
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.test.web.reactive.server.WebTestClient
import reactor.core.publisher.Flux

class ChartControllerTest {

    private val store = mockk<MarketDataStore>()
    private val repo = mockk<MarketCandleRepository>(relaxed = true)
    private val controller = ChartController(store, repo)
    private val client = WebTestClient.bindToController(controller).build()

    @Test
    fun `getCandles defaults exchange to UPBIT when query param omitted`() {
        every { store.getCandles(Exchange.UPBIT, "KRW-BTC", any(), any()) } returns emptyList()
        every { repo.findRecent("UPBIT", "KRW-BTC", any(), any()) } returns Flux.empty()

        client.get()
            .uri("/api/chart/candles?market=KRW-BTC&interval=1m")
            .exchange()
            .expectStatus().isOk

        verify { store.getCandles(Exchange.UPBIT, "KRW-BTC", any(), any()) }
    }

    @Test
    fun `getIndicators defaults exchange to UPBIT when query param omitted`() {
        every { store.getCandles(Exchange.UPBIT, "KRW-BTC", any(), any()) } returns emptyList()

        client.get()
            .uri("/api/chart/indicators?market=KRW-BTC&interval=1m")
            .exchange()
            .expectStatus().isOk
    }

    @Test
    fun `getCandles still respects explicit exchange parameter`() {
        every { store.getCandles(Exchange.BINANCE, "BTCUSDT", any(), any()) } returns emptyList()
        every { repo.findRecent("BINANCE", "BTCUSDT", any(), any()) } returns Flux.empty()

        client.get()
            .uri("/api/chart/candles?exchange=binance&market=BTCUSDT&interval=1m")
            .exchange()
            .expectStatus().isOk

        verify { store.getCandles(Exchange.BINANCE, "BTCUSDT", any(), any()) }
    }

    @Test
    fun `unknown exchange returns 400 instead of leaking 500`() {
        client.get()
            .uri("/api/chart/tickers?exchange=bogus")
            .exchange()
            .expectStatus().isBadRequest
    }

    @Test
    fun `unsupported interval label returns 400 instead of leaking 500`() {
        client.get()
            .uri("/api/chart/candles?market=KRW-BTC&interval=99x")
            .exchange()
            .expectStatus().isBadRequest
    }
}
