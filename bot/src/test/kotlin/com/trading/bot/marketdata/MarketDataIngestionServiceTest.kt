package com.trading.bot.marketdata

import com.trading.bot.config.WatchlistProperties
import com.trading.bot.stream.MarketDataPersistenceService
import com.trading.common.domain.Exchange
import com.trading.common.domain.NormalizedCandle
import com.trading.common.domain.NormalizedTicker
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
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
}
