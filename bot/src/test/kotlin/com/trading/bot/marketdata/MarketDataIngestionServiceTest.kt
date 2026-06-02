package com.trading.bot.marketdata

import com.trading.bot.config.WatchlistProperties
import com.trading.bot.stream.MarketDataPersistenceService
import com.trading.common.domain.CandleInterval
import com.trading.common.domain.Exchange
import com.trading.common.domain.NormalizedCandle
import com.trading.common.domain.NormalizedTicker
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Test

class MarketDataIngestionServiceTest {

    private val feed = mockk<UpbitMarketFeed>(relaxed = true)
    private val store = mockk<MarketDataStore>(relaxed = true)
    private val persistence = mockk<MarketDataPersistenceService>(relaxed = true)
    private val watchlist = mockk<WatchlistProperties>(relaxed = true)
    private val service = MarketDataIngestionService(feed, store, persistence, watchlist)

    private val ticker = NormalizedTicker(exchange = Exchange.UPBIT, market = "BTC/KRW", price = 50_000_000.0)
    private val candle = NormalizedCandle(
        exchange = Exchange.UPBIT, market = "BTC/KRW",
        openPrice = 1.0, highPrice = 2.0, lowPrice = 0.5, closePrice = 1.5, volume = 10.0,
    )

    // 한 sink(persistence) 실패가 다른 sink(store) 갱신이나 수집 코루틴을 죽이면 안 된다.
    @Test
    fun `ingestTicker updates store even when persistence throws`() {
        every { persistence.persistTicker(any()) } throws RuntimeException("db down")

        assertDoesNotThrow { service.ingestTicker(ticker) }

        verify { store.updateTicker(ticker) }
    }

    @Test
    fun `ingestTicker still persists even when store throws`() {
        every { store.updateTicker(any()) } throws RuntimeException("oom")

        assertDoesNotThrow { service.ingestTicker(ticker) }

        verify { persistence.persistTicker(ticker) }
    }

    @Test
    fun `ingestCandle updates store even when persistence throws`() {
        every { persistence.persistCandle(any()) } throws RuntimeException("db down")

        assertDoesNotThrow { service.ingestCandle(candle) }

        verify { store.addCandle(candle) }
    }

    @Test
    fun `ingestCandle still persists even when store throws`() {
        every { store.addCandle(any()) } throws RuntimeException("oom")

        assertDoesNotThrow { service.ingestCandle(candle) }

        verify { persistence.persistCandle(candle) }
    }

    // 부팅 백필: store D1 버퍼를 과거 일봉으로 채운다(매수/청산 warm-up REST 폴백 방지).
    @Test
    fun `seedDailyCandles loads D1 candles into store`() = runBlocking {
        coEvery { feed.getCandles("BTC/KRW", CandleInterval.D1, any()) } returns listOf(candle, candle)

        service.seedDailyCandles(listOf("BTC/KRW"))

        verify(exactly = 2) { store.addCandle(candle) }
    }

    @Test
    fun `seedDailyCandles isolates fetch failure across markets`() = runBlocking {
        coEvery { feed.getCandles("BTC/KRW", any(), any()) } throws RuntimeException("rate limit")
        val eth = candle.copy(market = "ETH/KRW")
        coEvery { feed.getCandles("ETH/KRW", CandleInterval.D1, any()) } returns listOf(eth)

        // 첫 market 실패가 둘째 market seed 를 막지 않는다.
        service.seedDailyCandles(listOf("BTC/KRW", "ETH/KRW"))

        verify { store.addCandle(eth) }
    }
}
