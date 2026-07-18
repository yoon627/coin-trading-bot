package com.trading.bot.stream

import com.trading.bot.marketdata.MarketDataStore
import com.trading.common.domain.CandleInterval
import com.trading.common.domain.Exchange
import com.trading.common.domain.NormalizedCandle
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Aggregation is observed through the MarketDataStore.addCandle sink: every M1
 * ingest emits one aggregated candle per interval, captured here without
 * touching the aggregator's private state.
 */
class CandleAggregatorTest {

    private lateinit var store: MarketDataStore
    private lateinit var aggregator: CandleAggregator
    private val captured = mutableListOf<NormalizedCandle>()

    @BeforeEach
    fun setup() {
        captured.clear()
        store = mockk(relaxed = true)
        every { store.addCandle(capture(captured)) } just Runs
        aggregator = CandleAggregator(store)
    }

    @Test
    fun `merges same-day M1 candles into one D1 candle`() {
        aggregator.onMinuteCandle(m1("2024-01-01T00:00:00Z", open = 100.0, high = 110.0, low = 95.0, close = 105.0, volume = 1.0, quoteVolume = 10.0))
        aggregator.onMinuteCandle(m1("2024-01-01T00:01:00Z", open = 105.0, high = 120.0, low = 100.0, close = 115.0, volume = 2.0, quoteVolume = 20.0))
        aggregator.onMinuteCandle(m1("2024-01-01T12:00:00Z", open = 115.0, high = 118.0, low = 90.0, close = 112.0, volume = 3.0, quoteVolume = 30.0))

        val d1 = capturedFor(CandleInterval.D1)
        assertEquals(inst("2024-01-01T00:00:00Z"), d1.map { it.openTime }.distinct().single())

        val merged = d1.last()
        assertEquals(100.0, merged.openPrice) // 최초 M1 open 보존
        assertEquals(120.0, merged.highPrice) // max
        assertEquals(90.0, merged.lowPrice) // min
        assertEquals(112.0, merged.closePrice) // last
        assertEquals(6.0, merged.volume) // 합
        assertEquals(60.0, merged.quoteVolume) // 합
    }

    @Test
    fun `aligns each interval to its period start`() {
        // 2024-01-17 = 수요일: interval 별 정렬 경계가 서로 달라(W1 월요일 01-15, MO1 01-01, D1 01-17)
        // 각 branch 를 독립 게이트한다. D1 정렬이 seed REST D1 openTime(UTC 자정)과 일치해야
        // MarketDataStore upsert dedup 이 성립 — 과거 D1 오염(d6c6857) 재발 방지 불변식의 전제.
        aggregator.onMinuteCandle(m1("2024-01-17T15:37:00Z", open = 1.0, high = 1.0, low = 1.0, close = 1.0, volume = 1.0))

        assertEquals(inst("2024-01-17T15:35:00Z"), openTimeOf(CandleInterval.M5))
        assertEquals(inst("2024-01-17T15:30:00Z"), openTimeOf(CandleInterval.M15))
        assertEquals(inst("2024-01-17T15:00:00Z"), openTimeOf(CandleInterval.H1))
        assertEquals(inst("2024-01-17T12:00:00Z"), openTimeOf(CandleInterval.H4))
        assertEquals(inst("2024-01-17T00:00:00Z"), openTimeOf(CandleInterval.D1))
        assertEquals(inst("2024-01-15T00:00:00Z"), openTimeOf(CandleInterval.W1))
        assertEquals(inst("2024-01-01T00:00:00Z"), openTimeOf(CandleInterval.MO1))
    }

    @Test
    fun `separates candles across the day boundary into distinct D1 periods`() {
        aggregator.onMinuteCandle(m1("2024-01-01T23:59:00Z", open = 1.0, high = 1.0, low = 1.0, close = 1.0, volume = 5.0))
        aggregator.onMinuteCandle(m1("2024-01-02T00:00:00Z", open = 2.0, high = 2.0, low = 2.0, close = 2.0, volume = 7.0))

        val byPeriod = capturedFor(CandleInterval.D1).groupBy { it.openTime }
        assertEquals(
            setOf(inst("2024-01-01T00:00:00Z"), inst("2024-01-02T00:00:00Z")),
            byPeriod.keys,
        )
        val day2 = byPeriod.getValue(inst("2024-01-02T00:00:00Z")).last()
        assertEquals(5.0, byPeriod.getValue(inst("2024-01-01T00:00:00Z")).last().volume)
        assertEquals(7.0, day2.volume) // 경계 넘어 병합되지 않음
        assertEquals(2.0, day2.openPrice) // 새 period 는 자기 봉 open 으로 시작
    }

    @Test
    fun `evicts D1 periods older than 3 intervals so a re-fed candle starts fresh`() {
        aggregator.onMinuteCandle(m1("2024-01-01T00:00:00Z", open = 1.0, high = 1.0, low = 1.0, close = 1.0, volume = 5.0))
        // Jan 5 진입 → cutoff=Jan2, Jan1 D1 key 축출.
        aggregator.onMinuteCandle(m1("2024-01-05T00:00:00Z", open = 1.0, high = 1.0, low = 1.0, close = 1.0, volume = 1.0))
        // Jan1 재유입 — 축출됐으므로 새 집계로 시작(cleanup 없으면 5+7=12).
        aggregator.onMinuteCandle(m1("2024-01-01T06:00:00Z", open = 9.0, high = 9.0, low = 9.0, close = 9.0, volume = 7.0))

        val jan1 = capturedFor(CandleInterval.D1).filter { it.openTime == inst("2024-01-01T00:00:00Z") }
        assertEquals(7.0, jan1.last().volume)
    }

    private fun capturedFor(interval: CandleInterval) = captured.filter { it.interval == interval }

    private fun openTimeOf(interval: CandleInterval) = capturedFor(interval).single().openTime

    private fun m1(openTime: String, open: Double, high: Double, low: Double, close: Double, volume: Double, quoteVolume: Double = 0.0) =
        NormalizedCandle(
            exchange = Exchange.UPBIT,
            market = MARKET,
            openPrice = open,
            highPrice = high,
            lowPrice = low,
            closePrice = close,
            volume = volume,
            quoteVolume = quoteVolume,
            interval = CandleInterval.M1,
            openTime = inst(openTime),
            closeTime = inst(openTime).plusSeconds(60),
        )

    private fun inst(iso: String) = Instant.parse(iso)

    companion object {
        private const val MARKET = "BTC/KRW"
    }
}
