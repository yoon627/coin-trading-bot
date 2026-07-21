package com.trading.bot.api

import com.trading.bot.config.MarketDataWatchdogProperties
import com.trading.bot.config.WatchlistProperties
import com.trading.bot.marketdata.MarketDataStore
import com.trading.common.domain.Exchange
import com.trading.common.domain.MarketPair
import com.trading.common.domain.NormalizedTicker
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reactor.test.StepVerifier
import java.time.Duration
import java.time.Instant

class PriceStreamControllerTest {

    private lateinit var store: MarketDataStore
    private lateinit var controller: PriceStreamController

    @BeforeEach
    fun setup() {
        store = MarketDataStore()
        controller = PriceStreamController(store, WatchlistProperties(), MarketDataWatchdogProperties())
    }

    // display market("KRW-BTC")을 정규화해 store 에 넣는다 — 컨트롤러가 다시 toUpbitFormat 으로 복원하는 왕복 검증.
    private fun put(displayMarket: String, price: Double, ts: Instant = Instant.now()) {
        store.updateTicker(
            NormalizedTicker(
                exchange = Exchange.UPBIT,
                market = MarketPair.normalize(Exchange.UPBIT, displayMarket),
                price = price,
                changeRate24h = 0.01,
                quoteVolume24h = 1e12,
                timestamp = ts,
            ),
        )
    }

    @Test
    fun `getLatestPrices returns all watchlist prices when no filter`() {
        put("KRW-BTC", 50000000.0)
        put("KRW-ETH", 3000000.0)

        val result = controller.getLatestPrices(null)
        assertEquals(2, result.size)
        assertTrue(result.containsKey("KRW-BTC"))
        assertTrue(result.containsKey("KRW-ETH"))
        assertEquals(50000000.0, result["KRW-BTC"]?.tradePrice)
    }

    @Test
    fun `getLatestPrices filters by tickers`() {
        put("KRW-BTC", 50000000.0)
        put("KRW-ETH", 3000000.0)
        put("KRW-XRP", 500.0)

        val result = controller.getLatestPrices(listOf("KRW-BTC", "KRW-ETH"))
        assertEquals(2, result.size)
        assertFalse(result.containsKey("KRW-XRP"))
    }

    @Test
    fun `getLatestPrices handles case insensitive tickers`() {
        put("KRW-BTC", 50000000.0)

        val result = controller.getLatestPrices(listOf("krw-btc"))
        assertEquals(1, result.size)
    }

    @Test
    fun `getConnectionStatus reports connected with fresh watchlist data`() {
        put("KRW-BTC", 50000000.0)

        val result = controller.getConnectionStatus()
        assertEquals(true, result["connected"])
        assertTrue((result["tickers"] as Set<*>).contains("KRW-BTC"))
    }

    @Test
    fun `getConnectionStatus reports disconnected when data is stale`() {
        // staleMs(기본 60s) 초과한 오래된 ticker 만 있으면 connected=false — WS isConnected 를 대체하는 freshness 신호.
        put("KRW-BTC", 50000000.0, ts = Instant.now().minusSeconds(3600))

        assertEquals(false, controller.getConnectionStatus()["connected"])
    }

    @Test
    fun `getLatestPrices excludes tickers outside watchlist even without filter`() {
        // 매매용 티커(KRW-DOGE)가 store 에 있어도 미인증 공개 응답에서 제외돼야 한다.
        put("KRW-BTC", 50000000.0)
        put("KRW-DOGE", 100.0)

        assertEquals(setOf("KRW-BTC"), controller.getLatestPrices(null).keys)
    }

    @Test
    fun `getConnectionStatus excludes tickers outside watchlist`() {
        put("KRW-BTC", 50000000.0)
        put("KRW-DOGE", 100.0)

        assertEquals(setOf("KRW-BTC"), controller.getConnectionStatus()["tickers"] as Set<*>)
    }

    @Test
    fun `streamPrices emits SSE for allowed ticker from store stream`() {
        val flux = controller.streamPrices(listOf("KRW-BTC"))
        StepVerifier.create(flux)
            .then { put("KRW-BTC", 50000000.0) }
            .expectNextMatches { it.id() == "KRW-BTC" && it.event() == "price" }
            .thenCancel()
            .verify(Duration.ofSeconds(3))
    }

    @Test
    fun `streamPrices returns non-null Flux for default watchlist`() {
        assertNotNull(controller.streamPrices(null))
    }
}
