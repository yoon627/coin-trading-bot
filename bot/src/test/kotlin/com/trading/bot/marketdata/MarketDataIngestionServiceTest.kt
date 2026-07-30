package com.trading.bot.marketdata

import com.trading.bot.config.MarketDataWatchdogProperties
import com.trading.bot.config.WatchlistProperties
import com.trading.bot.stream.MarketDataPersistenceService
import com.trading.common.domain.CandleInterval
import com.trading.common.domain.Exchange
import com.trading.common.domain.NormalizedCandle
import com.trading.common.domain.NormalizedTicker
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MarketDataIngestionServiceTest {

    private val feed = mockk<UpbitMarketFeed>(relaxed = true)
    private val store = mockk<MarketDataStore>(relaxed = true)
    private val persistence = mockk<MarketDataPersistenceService>(relaxed = true)
    private val watchlist = mockk<WatchlistProperties>(relaxed = true)
    private val service = MarketDataIngestionService(feed, store, persistence, watchlist, MarketDataWatchdogProperties())

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

    // ── rate limit (Upbit candles 그룹 = 초당 10회 / 분당 600회, 실측) ──
    // 마켓을 지연 없이 연속 호출하면 초당 상한을 넘겨 429 가 쏟아진다(운영에서 10분에 30건 관측).
    // virtual time 으로 "요청 사이에 실제로 간격이 있는지"를 본다 — 벽시계로 재면 느리고 flaky 하다.

    @Test
    fun `collectCandlesRound spaces requests across markets`() = runTest {
        val markets = listOf("BTC/KRW", "ETH/KRW", "XRP/KRW", "SOL/KRW")
        coEvery { feed.getCandles(any(), CandleInterval.M1, any()) } returns listOf(candle)

        val start = testScheduler.currentTime
        service.collectCandlesRound(markets)
        val elapsed = testScheduler.currentTime - start

        // N개 마켓이면 최소 (N-1) 번의 간격이 있어야 한다.
        val minExpected = (markets.size - 1) * MarketDataIngestionService.CANDLE_REQUEST_SPACING_MS
        assertTrue(
            elapsed >= minExpected,
            "요청 간 spacing 없음: elapsed=${elapsed}ms, 기대 최소=${minExpected}ms",
        )
    }

    @Test
    fun `seedDailyCandles spaces requests across markets`() = runTest {
        val markets = listOf("BTC/KRW", "ETH/KRW", "XRP/KRW")
        coEvery { feed.getCandles(any(), CandleInterval.D1, any()) } returns listOf(candle)

        val start = testScheduler.currentTime
        service.seedDailyCandles(markets)
        val elapsed = testScheduler.currentTime - start

        val minExpected = (markets.size - 1) * MarketDataIngestionService.CANDLE_REQUEST_SPACING_MS
        assertTrue(
            elapsed >= minExpected,
            "seed 요청 간 spacing 없음: elapsed=${elapsed}ms, 기대 최소=${minExpected}ms",
        )
    }

    // spacing 을 둬도 다른 경로와 겹치거나 업비트가 순간 제한을 좁히면 429 가 날 수 있다.
    // 그때 그 마켓의 1분봉을 그냥 버리지 않고 한 번은 되찾아야 한다.
    @Test
    fun `collectCandlesRound retries once after 429`() = runTest {
        coEvery { feed.getCandles("BTC/KRW", CandleInterval.M1, any()) }
            .throws(RuntimeException("429 Too Many Requests from GET https://api.upbit.com/v1/candles/minutes/1"))
            .andThen(listOf(candle))

        service.collectCandlesRound(listOf("BTC/KRW"))

        // 재시도가 성공해 store 에 반영된다.
        verify { store.addCandle(candle) }
    }

    // 429 가 아닌 오류는 재시도하지 않는다 — 원인이 사라지지 않으므로 낭비다.
    @Test
    fun `collectCandlesRound does not retry non-rate-limit errors`() = runTest {
        coEvery { feed.getCandles("BTC/KRW", CandleInterval.M1, any()) } throws RuntimeException("connection reset")

        service.collectCandlesRound(listOf("BTC/KRW"))

        coVerify(exactly = 1) { feed.getCandles("BTC/KRW", CandleInterval.M1, any()) }
    }
}
