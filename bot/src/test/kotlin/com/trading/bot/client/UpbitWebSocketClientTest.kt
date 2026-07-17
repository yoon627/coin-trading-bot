package com.trading.bot.client

import com.trading.bot.config.MarketDataWatchdogProperties
import com.trading.bot.config.WatchlistProperties
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class UpbitWebSocketClientTest {

    // autoConnect=false 로 실제 WS 연결을 억제 — 단위 테스트는 구독 목록만 관찰(§7 네트워크 회피).
    private fun client(tickers: String = "KRW-BTC,KRW-ETH,KRW-XRP,KRW-SOL") =
        UpbitWebSocketClient(WatchlistProperties(tickers = tickers), MarketDataWatchdogProperties(), autoConnect = false)

    @Test
    fun `latestPrice returns null when no data received`() {
        assertNull(client().latestPrice("KRW-BTC"))
    }

    @Test
    fun `allLatestPrices returns empty map initially`() {
        assertTrue(client().allLatestPrices().isEmpty())
    }

    @Test
    fun `isConnected returns false initially`() {
        assertFalse(client().isConnected())
    }

    @Test
    fun `subscribe adds tickers without error`() {
        val c = client()
        assertDoesNotThrow { c.subscribe(listOf("KRW-BTC", "KRW-ETH")) }
    }

    @Test
    fun `priceFlow returns a Flux`() {
        assertNotNull(client().priceFlow())
    }

    // --- 1단계: init 이 하드코딩 4종목이 아니라 watchlist 를 구독 ---

    @Test
    fun `init subscribes to configured watchlist, not hardcoded defaults`() {
        val c = UpbitWebSocketClient(
            WatchlistProperties(tickers = "KRW-DOGE,KRW-ADA"),
            MarketDataWatchdogProperties(),
            autoConnect = false,
        )
        c.init()
        assertEquals(setOf("KRW-DOGE", "KRW-ADA"), c.subscribedMarkets())
    }

    @Test
    fun `subscribe registers new tickers on top of watchlist for fallback coverage`() {
        val c = client(tickers = "KRW-BTC")
        c.init()
        c.subscribe(listOf("KRW-DOGE"))
        assertEquals(setOf("KRW-BTC", "KRW-DOGE"), c.subscribedMarkets())
    }

    // 세대가드: 무효화된(낡은) 세대의 doFinally/scheduleReconnect 는 재연결하지 않는다.
    @Test
    fun `shouldActForGeneration only when generation matches and not shutting down`() {
        assertTrue(UpbitWebSocketClient.shouldActForGeneration(myGen = 5, currentGen = 5, shuttingDown = false))
        assertFalse(UpbitWebSocketClient.shouldActForGeneration(myGen = 4, currentGen = 5, shuttingDown = false))
        assertFalse(UpbitWebSocketClient.shouldActForGeneration(myGen = 5, currentGen = 5, shuttingDown = true))
    }
}
