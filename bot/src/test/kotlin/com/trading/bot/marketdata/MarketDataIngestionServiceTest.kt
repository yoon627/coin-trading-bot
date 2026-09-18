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
import io.mockk.verifyOrder
import java.time.Instant
import java.time.temporal.ChronoUnit
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

    // #209: seed 의 오늘(UTC) 봉은 이후 M1 이 이어서 집계할 period 라 집계기에 prime 한다 — 안 하면 첫 M1 이 seed 를 대체한다.
    // 응답 순서에 기대지 않고 openTime == 오늘 자정으로 고른다(mock 은 일부러 오래된 봉을 앞에 둔다).
    @Test
    fun `seedDailyCandles primes the aggregator with today's seeded candle only`() = runBlocking {
        val today = Instant.now().truncatedTo(ChronoUnit.DAYS)
        val todayCandle = candle.copy(openTime = today, closeTime = today.plusSeconds(86_400))
        val yesterday = candle.copy(openTime = today.minusSeconds(86_400), closeTime = today)
        coEvery { feed.getCandles("BTC/KRW", CandleInterval.D1, any()) } returns listOf(yesterday, todayCandle)

        service.seedDailyCandles(listOf("BTC/KRW"))

        verify(exactly = 1) { persistence.primeAggregate(todayCandle, any()) }
        verify(exactly = 0) { persistence.primeAggregate(yesterday, any()) }
    }

    @Test
    fun `seedDailyCandles skips priming when today's candle is absent and still seeds the store`() = runBlocking {
        val yesterday = candle.copy(openTime = Instant.now().truncatedTo(ChronoUnit.DAYS).minusSeconds(86_400))
        coEvery { feed.getCandles("BTC/KRW", CandleInterval.D1, any()) } returns listOf(yesterday)

        service.seedDailyCandles(listOf("BTC/KRW"))

        verify(exactly = 1) { store.addCandle(yesterday) }
        verify(exactly = 0) { persistence.primeAggregate(any(), any()) }
    }

    // 첫 라운드 꼬리(count>1)가 어제 분봉을 실어 오면 상태 없는 어제 period 가 부분봉으로 store 의 seed 를 덮는다 —
    // 바닥은 prime(오늘 행이 있을 때만) 이 아니라 seed 시점에 항상, fetch 가 실패해도 건다.
    @Test
    fun `seedDailyCandles raises the aggregation floor even without today's row and even when the fetch fails`() = runBlocking {
        val yesterday = candle.copy(openTime = Instant.now().truncatedTo(ChronoUnit.DAYS).minusSeconds(86_400))
        coEvery { feed.getCandles("BTC/KRW", CandleInterval.D1, any()) } returns listOf(yesterday)
        coEvery { feed.getCandles("ETH/KRW", CandleInterval.D1, any()) } throws RuntimeException("rate limit")

        service.seedDailyCandles(listOf("BTC/KRW", "ETH/KRW"))

        verify(exactly = 1) { persistence.startAggregationFrom("BTC/KRW", any()) }
        verify(exactly = 1) { persistence.startAggregationFrom("ETH/KRW", any()) }
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

    // Upbit 은 최신순으로 돌려주지만 집계기는 "더 새 분봉이 오면 이전 분봉을 확정" 하므로 오름차순으로 넣어야 한다 —
    // 내림차순이면 부분 봉이 확정되고 완결본은 오래된 봉으로 무시된다.
    @Test
    fun `collectCandlesRound ingests polled M1 candles oldest first and asks for the fetch count on both paths`() = runTest {
        val t = Instant.parse("2024-01-01T00:13:00Z")
        val newest = candle.copy(openTime = t, closeTime = t.plusSeconds(60))
        val middle = candle.copy(openTime = t.minusSeconds(60), closeTime = t)
        val oldest = candle.copy(openTime = t.minusSeconds(120), closeTime = t.minusSeconds(60))
        coEvery { feed.getCandles("BTC/KRW", CandleInterval.M1, MarketDataIngestionService.M1_FETCH_COUNT) }
            .throws(RuntimeException("429 Too Many Requests"))
            .andThen(listOf(newest, middle, oldest))

        service.collectCandlesRound(listOf("BTC/KRW"))

        verifyOrder {
            store.addCandle(oldest)
            store.addCandle(middle)
            store.addCandle(newest)
        }
        coVerify(exactly = 2) { feed.getCandles("BTC/KRW", CandleInterval.M1, MarketDataIngestionService.M1_FETCH_COUNT) }
    }

    // 상수 단정 — 1 로 되돌리면 진행 중 분봉만 와서 각 분의 완결본을 영영 못 받는다(실측: 일봉 volume 이 Upbit 의 중앙값 66%). 동작 테스트가 아니라 회귀 표지.
    @Test
    fun `M1 fetch count exceeds one so completed minutes arrive in the tail`() {
        assertTrue(MarketDataIngestionService.M1_FETCH_COUNT > 1)
    }

    // 429 가 아닌 오류는 재시도하지 않는다 — 원인이 사라지지 않으므로 낭비다.
    @Test
    fun `collectCandlesRound does not retry non-rate-limit errors`() = runTest {
        coEvery { feed.getCandles("BTC/KRW", CandleInterval.M1, any()) } throws RuntimeException("connection reset")

        service.collectCandlesRound(listOf("BTC/KRW"))

        coVerify(exactly = 1) { feed.getCandles("BTC/KRW", CandleInterval.M1, any()) }
    }
}
