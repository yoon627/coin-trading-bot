package com.trading.bot.engine

import com.trading.bot.engine.LiveSemanticsArm.EntryFilter
import com.trading.common.config.TradingProperties
import com.trading.common.domain.Candle
import com.trading.common.strategy.TradingStrategy
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [LiveSemanticsArm.run] 의 [EntryFilter] — #208 진입 조건 재판정의 family 축. **시가 규칙**: 돌파선' = 돌파선×(1+m), 돌파 봉 B = 그날
 * 시가 > 돌파선' 인 첫 봉, 후보 = B 뒤 ceil(지연분/봉길이) 봉 이후에 시가 > 돌파선' 이고 봉 시작이 마감 전인 봉, 후보마다 `shouldBuy` 재평가, 체결 = 시가.
 * 봉 시작에 알려진 값만 쓰므로 봉 안 경로 가정이 없다. 기본 [EntryFilter.NONE] 은 기존 코드 경로 그대로여야 한다(다른 측정의 핀이 그 위에 서 있다).
 *
 * 합성 봉: 당일시가 = **그날 첫 봉의 시가**(계기 규약)라 첫 봉은 항상 100 에서 열고, 돌파선 = 100 + 전일 레인지 10 × 0.5 = 105. 봉은 240분 격자 — 지연 240/480/720분 = 1/2/3봉.
 */
class LiveSemanticsArmEntryFilterTest {

    private val always = object : TradingStrategy {
        override val name = "always"
        override suspend fun shouldBuy(candles: List<Candle>, currentPrice: Double, config: TradingProperties) = true
    }
    private val props = TradingProperties()
    private val config = StrategySearchGrid.currentLivePoint().toConfig()
    private val warmup = BacktestEngine.MIN_CANDLES
    private val entryDay = "2024-02-20"
    private val nextDay = "2024-02-21"

    private fun daily(): List<Candle> {
        val entry = LocalDate.parse(entryDay)
        val out = ArrayList<Candle>()
        for (back in warmup downTo 2) out += flat(entry.minusDays(back.toLong()).toString(), 100.0)
        out += Candle(candleDateTimeKst = "${entry.minusDays(1)}T09:00:00", openingPrice = 100.0, highPrice = 110.0, lowPrice = 100.0, tradePrice = 100.0)
        out += flat(entryDay, 100.0)
        out += flat(nextDay, 109.5)
        return out
    }

    private fun flat(date: String, p: Double) = Candle(candleDateTimeKst = "${date}T09:00:00", openingPrice = p, highPrice = p, lowPrice = p, tradePrice = p)

    private fun bar(hour: Int, open: Double, high: Double, low: Double = open, close: Double = high, date: String = entryDay) = Candle(
        candleDateTimeUtc = "${date}T%02d:00:00".format(hour), openingPrice = open, highPrice = high, lowPrice = low, tradePrice = close,
    )

    private fun tail() = listOf(bar(20, 109.5, 109.5), bar(0, 109.5, 109.5, date = nextDay))
    private val open100 = bar(0, 100.0, 104.0)

    /** h0 시가 100(당일시가), h4 시가 106 (돌파 봉 B), h8 시가 104 (되밀림), h12 시가 106, h16 시가 107, 이후 평탄 109.5, 다음날 09:00 109.5. */
    private fun pullbackDay(): List<Candle> = listOf(open100, bar(4, 106.0, 107.0), bar(8, 104.0, 105.5), bar(12, 106.0, 107.0), bar(16, 107.0, 108.0)) + tail()

    private fun run(bars: List<Candle>, filter: EntryFilter, strategy: TradingStrategy = always) = runBlocking {
        LiveSemanticsArm.run("KRW-TEST", strategy, daily(), bars, config, props, entryFilter = filter)
    }

    private fun entry(bars: List<Candle>, filter: EntryFilter, strategy: TradingStrategy = always) = run(bars, filter, strategy).singleOrNull { it.entryDate == entryDay }

    @Test
    fun `NONE is the legacy path and yields the same trade list as omitting the parameter`() {
        val bars = pullbackDay()
        val default = runBlocking { LiveSemanticsArm.run("KRW-TEST", always, daily(), bars, config, props) }
        assertEquals(default, run(bars, EntryFilter.NONE))
        val t = entry(bars, EntryFilter.NONE)!!
        assertEquals(106.0, t.entryPrice, 1e-9, "기존: 고가 > 돌파선인 첫 봉(h4)에서 max(돌파선, 시가)")
        assertEquals("${entryDay}T04:00:00", t.entryBarUtc)
        assertEquals(0, t.entryDelayBars); assertEquals(0, t.entrySignalDeferrals)
    }

    @Test
    fun `legacy path can fill at the breakout line when the bar opens below it but the open rule never does`() {
        val bars = listOf(bar(0, 100.0, 106.0), bar(4, 104.0, 104.5), bar(8, 106.0, 107.0), bar(12, 107.0, 108.0), bar(16, 109.5, 109.5)) + tail()
        assertEquals(105.0, entry(bars, EntryFilter.NONE)!!.entryPrice, 1e-9, "기존 경로: 시가 100 < 105 < 고가 106 → 돌파선 체결(`always` 전략)")
        val neutral = entry(bars, EntryFilter(confirmDelayMinutes = 0))!!
        assertEquals("${entryDay}T08:00:00", neutral.entryBarUtc, "시가 규칙: 시가 > 105 인 첫 봉 h8")
        assertEquals(106.0, neutral.entryPrice, 1e-9)
    }

    @Test
    fun `confirmDelay enters at the open of the first candidate bar at least ceil(minutes per bar) bars after the breakout bar`() {
        val bars = pullbackDay()
        val d1 = entry(bars, EntryFilter(confirmDelayMinutes = 240))!!
        assertEquals("${entryDay}T12:00:00", d1.entryBarUtc, "h8 은 시가 104 ≤ 105 라 후보 아님, h12 시가 106 에서 체결")
        assertEquals(106.0, d1.entryPrice, 1e-9)
        assertEquals(2, d1.entryDelayBars)
        assertEquals("${entryDay}T12:00:00", entry(bars, EntryFilter(confirmDelayMinutes = 480))!!.entryBarUtc)
        val d3 = entry(bars, EntryFilter(confirmDelayMinutes = 720))!!
        assertEquals("${entryDay}T16:00:00", d3.entryBarUtc)
        assertEquals(107.0, d3.entryPrice, 1e-9)
        assertEquals(d1, entry(bars, EntryFilter(confirmDelayMinutes = 1)), "1분도 봉 길이로 올림 → 1봉")
    }

    @Test
    fun `a breakout on the last bar of the day with a delay yields no entry that day`() {
        val bars = listOf(open100, bar(4, 103.0, 104.0), bar(8, 104.0, 104.5), bar(12, 104.0, 104.9), bar(16, 104.0, 104.9), bar(20, 106.0, 107.0), bar(0, 109.5, 109.5, date = nextDay))
        assertEquals("${entryDay}T20:00:00", entry(bars, EntryFilter.NONE)!!.entryBarUtc)
        assertNull(entry(bars, EntryFilter(confirmDelayMinutes = 240)))
    }

    @Test
    fun `abandonOnPullback gives up the day once a pre-entry bar after the breakout bar opens at or below the line`() {
        assertNull(entry(pullbackDay(), EntryFilter(confirmDelayMinutes = 240, abandonOnPullback = true)), "h8 시가 104 ≤ 105 → 그날 포기")
        val noPullback = listOf(open100, bar(4, 106.0, 107.0), bar(8, 105.5, 106.0), bar(12, 106.0, 107.0), bar(16, 107.0, 108.0)) + tail()
        val t = entry(noPullback, EntryFilter(confirmDelayMinutes = 240, abandonOnPullback = true))!!
        assertEquals("${entryDay}T08:00:00", t.entryBarUtc, "되밀림 없으면 지연 1봉과 같다")
        assertEquals(105.5, t.entryPrice, 1e-9)
        assertEquals(t, entry(noPullback, EntryFilter(confirmDelayMinutes = 240)))
    }

    @Test
    fun `marginPct raises the line used to detect the breakout bar and still fills at the open`() {
        val bars = listOf(open100, bar(4, 106.0, 107.0), bar(8, 104.0, 105.0), bar(12, 106.5, 107.0), bar(16, 107.0, 108.0)) + tail()
        assertEquals("${entryDay}T04:00:00", entry(bars, EntryFilter.NONE)!!.entryBarUtc)
        val t = entry(bars, EntryFilter(marginPct = 1.0))!!
        assertEquals("${entryDay}T12:00:00", t.entryBarUtc, "h4 시가 106 ≤ 106.05 라 돌파 봉 아님, h12 시가 106.5 가 B")
        assertEquals(106.5, t.entryPrice, 1e-9, "체결은 시가 — 돌파선' 106.05 체결이 아니다")
    }

    @Test
    fun `entryCutoffMinutes blocks candidate bars starting at or after the cutoff measured from 00 UTC`() {
        val bars = listOf(open100, bar(4, 103.0, 104.0), bar(8, 104.0, 104.5), bar(12, 106.0, 107.0), bar(16, 109.5, 109.5)) + tail()
        assertEquals("${entryDay}T12:00:00", entry(bars, EntryFilter.NONE)!!.entryBarUtc)
        assertNull(entry(bars, EntryFilter(entryCutoffMinutes = 12 * 60)), "12h 마감: 12:00 봉은 마감 이후")
        assertEquals("${entryDay}T12:00:00", entry(bars, EntryFilter(entryCutoffMinutes = 13 * 60))!!.entryBarUtc)
    }

    @Test
    fun `shouldBuy is re-evaluated on each candidate bar and rejections are counted as deferrals`() {
        val above = object : TradingStrategy {
            override val name = "above-106.5"
            override suspend fun shouldBuy(candles: List<Candle>, currentPrice: Double, config: TradingProperties) = currentPrice >= 106.5
        }
        val bars = listOf(open100, bar(4, 106.0, 107.0), bar(8, 106.2, 107.0), bar(12, 107.0, 108.0), bar(16, 107.0, 108.0)) + tail()
        val t = entry(bars, EntryFilter(confirmDelayMinutes = 0), above)!!
        assertEquals("${entryDay}T12:00:00", t.entryBarUtc)
        assertEquals(2, t.entrySignalDeferrals, "h4·h8 후보가 신호 거부로 밀렸다")
        assertEquals(2, t.entryDelayBars)
    }

    @Test
    fun `filters change only the entry — the exit path after a delayed entry follows the unchanged rules`() {
        val t = entry(pullbackDay(), EntryFilter(confirmDelayMinutes = 240))!!
        assertEquals("TIME_EXIT", t.reason)
        assertEquals(nextDay, t.exitDate)
        assertEquals(109.5, t.exitPrice, 1e-9)
        assertTrue(!t.exitOnEntryBar)
    }
}
