package com.trading.bot.marketdata

import com.trading.bot.config.MarketDataWatchdogProperties
import com.trading.bot.config.WatchlistProperties
import com.trading.bot.stream.CandleAggregator
import com.trading.common.domain.CandleInterval
import com.trading.common.domain.Exchange
import com.trading.common.domain.NormalizedCandle
import io.mockk.coEvery
import io.mockk.mockk
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * 폴링 → 집계기 → store 의 D1 경로를 실제 [MarketDataStore]·[CandleAggregator] 로 본다. 엔진은 store 의 D1 로 목표가·RSI·당일 봉을
 * 판정하므로 분봉 집계, 오늘 행이 없는 seed 의 바닥([CandleAggregator.startFrom]), 오늘 seed 의 등록([CandleAggregator.prime]) 중
 * 하나라도 빠지면 매매 판단이 바뀐다. 오늘 행이 있으면 prime 도 바닥을 올리므로 바닥은 오늘 행이 없는 seed 로만 따로 드러난다.
 */
class MarketDataIngestionAggregationTest {

    private val feed = mockk<UpbitMarketFeed>(relaxed = true)
    private val store = MarketDataStore()
    private val service = MarketDataIngestionService(
        feed, store, CandleAggregator(store), mockk<WatchlistProperties>(relaxed = true), MarketDataWatchdogProperties(),
    )

    @BeforeEach
    fun awayFromUtcMidnight() {
        // seed 가 Instant.now() 로 오늘을 정한다 — 테스트가 자정을 넘나들면 오늘·어제 판정이 서로 갈린다.
        val now = Instant.now()
        val untilMidnight = Duration.between(now, now.truncatedTo(ChronoUnit.DAYS).plus(1, ChronoUnit.DAYS))
        if (untilMidnight < Duration.ofSeconds(5)) Thread.sleep(untilMidnight.toMillis() + 1_000)
    }

    private fun minute(openTime: Instant, open: Double, high: Double, low: Double, close: Double, volume: Double) = NormalizedCandle(
        exchange = Exchange.UPBIT, market = MARKET, openPrice = open, highPrice = high, lowPrice = low, closePrice = close,
        volume = volume, interval = CandleInterval.M1, openTime = openTime, closeTime = openTime.plusSeconds(60),
    )

    private fun daily(openTime: Instant, high: Double, low: Double, close: Double, volume: Double) = NormalizedCandle(
        exchange = Exchange.UPBIT, market = MARKET, openPrice = 100.0, highPrice = high, lowPrice = low, closePrice = close,
        volume = volume, interval = CandleInterval.D1, openTime = openTime, closeTime = openTime.plus(1, ChronoUnit.DAYS),
    )

    private fun seed(vararg candles: NormalizedCandle) = runBlocking {
        coEvery { feed.getCandles(MARKET, CandleInterval.D1, any()) } returns candles.toList()
        service.seedDailyCandles(listOf(MARKET))
    }

    // Upbit 처럼 최신순으로 돌려준다.
    private fun pollRound(vararg newestFirst: NormalizedCandle) = runBlocking {
        coEvery { feed.getCandles(MARKET, CandleInterval.M1, any()) } returns newestFirst.toList()
        service.collectCandlesRound(listOf(MARKET))
    }

    private fun storedDaily(openTime: Instant): NormalizedCandle? =
        store.getCandles(Exchange.UPBIT, MARKET, CandleInterval.D1, 10).firstOrNull { it.openTime == openTime }

    @Test
    fun `polled minutes fold into the store's D1`() {
        val day = Instant.parse("2024-01-01T00:00:00Z")
        pollRound(
            minute(day.plusSeconds(120), 115.0, 116.0, 90.0, 99.0, 3.0),
            minute(day.plusSeconds(60), 105.0, 120.0, 101.0, 115.0, 2.0),
            minute(day, 100.0, 110.0, 95.0, 105.0, 1.0),
        )

        val d1 = storedDaily(day)!!
        assertEquals(listOf(100.0, 120.0, 90.0, 99.0, 6.0), listOf(d1.openPrice, d1.highPrice, d1.lowPrice, d1.closePrice, d1.volume))
    }

    @Test
    fun `after a seed without today's row a polled minute of yesterday does not overwrite yesterday's seed`() {
        val today = Instant.now().truncatedTo(ChronoUnit.DAYS)
        val yesterday = daily(today.minus(1, ChronoUnit.DAYS), high = 130.0, low = 80.0, close = 120.0, volume = 500.0)
        seed(yesterday)

        pollRound(minute(today.minusSeconds(600), 120.0, 121.0, 119.0, 120.5, 0.1))

        assertEquals(yesterday, storedDaily(yesterday.openTime))
    }

    @Test
    fun `a polled round after the seed adds only the minutes the seed does not already hold`() {
        val today = Instant.now().truncatedTo(ChronoUnit.DAYS)
        // seed 응답 시각과 아래 현재 분이 같은 분이어야 "seed 에 이미 든 분"이 정해진다.
        val second = Instant.now().atZone(ZoneOffset.UTC).second
        if (second >= 58) Thread.sleep((61 - second) * 1_000L)
        seed(daily(today, high = 130.0, low = 80.0, close = 120.0, volume = 500.0))
        val current = Instant.now().truncatedTo(ChronoUnit.MINUTES)

        // 실제 라운드처럼 seed 에 이미 든 앞의 분들이 꼬리로 함께 온다 — 다시 더하면 그만큼 부풀고, 시드가 아니라 첫 분봉부터 새로 시작하면 seed 가 사라진다.
        pollRound(
            minute(current, 120.0, 140.0, 119.0, 135.0, 2.0),
            minute(current.minusSeconds(60), 118.0, 150.0, 70.0, 120.0, 7.0),
            minute(current.minusSeconds(120), 117.0, 160.0, 60.0, 118.0, 11.0),
        )

        val d1 = storedDaily(today)!!
        assertEquals(listOf(140.0, 80.0, 135.0, 502.0), listOf(d1.highPrice, d1.lowPrice, d1.closePrice, d1.volume))
    }

    private companion object {
        const val MARKET = "BTC/KRW"
    }
}
