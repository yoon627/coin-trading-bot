package com.trading.bot.engine

import com.trading.bot.engine.CompletedBarCombined.Indicator
import com.trading.bot.engine.CompletedBarCombined.Mode
import com.trading.common.config.TradingProperties
import com.trading.common.domain.Candle
import com.trading.common.strategy.CombinedStrategy
import com.trading.common.strategy.Indicators
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CompletedBarCombinedTest {

    private val props = TradingProperties()
    private val combined = CombinedStrategy()

    // newest-first window = [당일 부분봉] + 완결 49봉 — LiveSemanticsArm.partialWindow() 와 같은 모양. 완결 봉 레인지 1 → 돌파선 = 시가 + 0.5.
    private fun window(completedCloses: List<Double>, partialOpen: Double, partialClose: Double): List<Candle> {
        val completed = completedCloses.mapIndexed { i, c ->
            Candle(market = "KRW-TEST", candleDateTimeKst = "2024-01-%02dT09:00:00".format(1 + i % 28), openingPrice = c, highPrice = c + 0.5, lowPrice = c - 0.5, tradePrice = c)
        }
        val partial = Candle(market = "KRW-TEST", candleDateTimeKst = "2024-03-01T09:00:00", openingPrice = partialOpen,
            highPrice = maxOf(partialOpen, partialClose), lowPrice = minOf(partialOpen, partialClose), tradePrice = partialClose)
        return listOf(partial) + completed.asReversed()
    }

    // 진동 완결 봉 위에 부분봉 종가가 튀면 MA5 만 올라간다 — 부분 MA 통과·완결 MA 실패, RSI 는 둘 다 범위 안.
    private val maFlip = window(List(49) { 100 + if (it % 2 == 1) 1.0 else -1.0 }, partialOpen = 100.0, partialClose = 103.0)
    // 완만한 상승 위에 부분봉이 크게 튀면 RSI 만 70 을 넘는다 — 부분 RSI 거부·완결 RSI 통과, MA 는 둘 다 통과.
    private val rsiFlip = window(List(49) { 100 + 0.4 * it + if (it % 2 == 1) 1.2 else -1.2 }, partialOpen = 118.0, partialClose = 134.0)

    private fun buy(s: CompletedBarCombined, w: List<Candle>) = runBlocking { s.shouldBuy(w, w.first().close, props) }

    @Test
    fun `synthetic windows flip exactly the intended indicator`() {
        assertTrue(Indicators.isMaUptrend(maFlip, 5, 20)); assertFalse(Indicators.isMaUptrend(maFlip.drop(1), 5, 20))
        assertTrue(Indicators.calculateRsi(maFlip, 14) in 30.0..70.0); assertTrue(Indicators.calculateRsi(maFlip.drop(1), 14) in 30.0..70.0)
        assertTrue(Indicators.isMaUptrend(rsiFlip, 5, 20)); assertTrue(Indicators.isMaUptrend(rsiFlip.drop(1), 5, 20))
        assertTrue(Indicators.calculateRsi(rsiFlip, 14) > 70.0); assertTrue(Indicators.calculateRsi(rsiFlip.drop(1), 14) in 30.0..70.0)
    }

    @Test
    fun `identity cell decides exactly like combined`() = runBlocking {
        val identity = CompletedBarCombined(Mode.PARTIAL, Mode.PARTIAL)
        for (w in listOf(maFlip, rsiFlip)) for (price in listOf(w.first().open + 0.4, w.first().close)) {
            assertEquals(combined.shouldBuy(w, price, props), identity.shouldBuy(w, price, props))
        }
        assertEquals("combined", identity.name)
        assertEquals(combined.minCandles, identity.minCandles)
    }

    @Test
    fun `completed modes drop the partial bar only for the chosen indicator`() {
        assertTrue(buy(CompletedBarCombined(Mode.PARTIAL, Mode.PARTIAL), maFlip))
        assertFalse(buy(CompletedBarCombined(Mode.COMPLETED, Mode.PARTIAL), maFlip))
        assertTrue(buy(CompletedBarCombined(Mode.PARTIAL, Mode.COMPLETED), maFlip))
        assertFalse(buy(CompletedBarCombined(Mode.COMPLETED, Mode.COMPLETED), maFlip))

        assertFalse(buy(CompletedBarCombined(Mode.PARTIAL, Mode.PARTIAL), rsiFlip))
        assertFalse(buy(CompletedBarCombined(Mode.COMPLETED, Mode.PARTIAL), rsiFlip))
        assertTrue(buy(CompletedBarCombined(Mode.PARTIAL, Mode.COMPLETED), rsiFlip))
        assertTrue(buy(CompletedBarCombined(Mode.COMPLETED, Mode.COMPLETED), rsiFlip))
    }

    @Test
    fun `breakout guard is unchanged — no buy at or below the target in any mode`() {
        for (ma in Mode.values()) for (rsi in Mode.values()) {
            val s = CompletedBarCombined(ma, rsi) { _, _, _ -> true }
            assertFalse(runBlocking { s.shouldBuy(maFlip, maFlip.first().open + 0.5, props) }, "$ma/$rsi")
        }
    }

    @Test
    fun `flipped mode XORs the partial condition with the mask for that market-day`() {
        val seen = ArrayList<Triple<Indicator, String, String>>()
        val flipAll = CompletedBarCombined(Mode.FLIPPED, Mode.PARTIAL) { i, m, d -> seen += Triple(i, m, d); true }
        assertFalse(buy(flipAll, maFlip), "부분 MA 통과 xor true = 실패")
        assertEquals(Triple(Indicator.MA, "KRW-TEST", "2024-03-01"), seen.single())
        assertTrue(buy(CompletedBarCombined(Mode.FLIPPED, Mode.PARTIAL) { _, _, _ -> false }, maFlip), "마스크 false = 기준")
        assertTrue(buy(CompletedBarCombined(Mode.PARTIAL, Mode.FLIPPED) { _, _, _ -> true }, rsiFlip), "부분 RSI 거부 xor true = 통과")
    }

    @Test
    fun `supplied mode uses the given value instead of any window condition`() {
        assertTrue(buy(CompletedBarCombined(Mode.SUPPLIED, Mode.PARTIAL) { _, _, _ -> true }, maFlip))
        assertFalse(buy(CompletedBarCombined(Mode.SUPPLIED, Mode.PARTIAL) { _, _, _ -> false }, maFlip))
        assertTrue(buy(CompletedBarCombined(Mode.PARTIAL, Mode.SUPPLIED) { _, _, _ -> true }, rsiFlip), "부분 RSI 는 거부지만 공급값 true")
    }

    @Test
    fun `completed mode records the completed value per market-day for the plumbing cross-check`() {
        val s = CompletedBarCombined(Mode.COMPLETED, Mode.COMPLETED)
        buy(s, rsiFlip)
        assertEquals(true, s.observed[Triple("KRW-TEST", "2024-03-01", Indicator.MA)])
        assertEquals(true, s.observed[Triple("KRW-TEST", "2024-03-01", Indicator.RSI)])
        buy(s, rsiFlip)
        assertEquals(0, s.inconsistent, "같은 (마켓, 날) 의 완결 값은 봉마다 같아야 한다")
    }

    // ── 사전계산 게이트 ──

    private fun daily(n: Int): List<Candle> = List(n) { i ->
        val c = 100 + 5 * kotlin.math.sin(i / 3.0) + 0.1 * i
        Candle(market = "KRW-TEST", candleDateTimeKst = java.time.LocalDate.of(2024, 1, 1).plusDays(i.toLong()).toString() + "T09:00:00",
            openingPrice = c, highPrice = c + 1, lowPrice = c - 1, tradePrice = c)
    }

    @Test
    fun `precomputed completed condition equals the strategy's drop-1 value on the instrument's slice`() {
        val d = daily(80)
        val gate = CompletedBarGate.build(d, warmup = 50)
        assertEquals(30, gate.days.size)
        assertEquals(d[50].candleDateTimeKst.substring(0, 10), gate.days.first())
        for ((i, day) in gate.days.withIndex()) {
            val dayIndex = 50 + i
            // LiveSemanticsArm: recentCompleted = daily[dayIndex-49 until dayIndex], window = partial + 그것의 역순
            val completedNewestFirst = d.subList(dayIndex - 49, dayIndex).asReversed()
            val partial = d[dayIndex].copy(candleDateTimeKst = "${day}T09:00:00")
            val w = listOf(partial) + completedNewestFirst
            assertEquals(Indicators.isMaUptrend(w.drop(1), 5, 20), gate.values.getValue(Indicator.MA)[i], day)
            assertEquals(Indicators.calculateRsi(w.drop(1), 14) in 30.0..70.0, gate.values.getValue(Indicator.RSI)[i], day)
        }
    }

    @Test
    fun `transition mask marks days whose next completed value differs and the last day is false`() {
        val v = booleanArrayOf(true, true, false, false, true, true)
        assertEquals(listOf(false, true, false, true, false, false), CompletedBarGate.transitions(v).toList())
    }

    @Test
    fun `circular shift maps index i to i plus k and preserves the multiset`() {
        val m = booleanArrayOf(true, false, false, true, false)
        assertEquals(listOf(false, true, false, true, false), CompletedBarGate.shifted(m, 2).toList()) // out[i] = m[(i+2) mod 5]
        assertEquals(CompletedBarGate.shifted(m, 2).toList(), CompletedBarGate.shifted(m, 7).toList(), "k > n 는 k mod n")
        assertEquals(m.count { it }, CompletedBarGate.shifted(m, 3).count { it })
        assertEquals(m.toList(), CompletedBarGate.shifted(m, 0).toList())
    }

    @Test
    fun `shift schedule is 17 to 74 in steps of 3 and every circular distance on 150 days is at least 15`() {
        val ks = CompletedBarGate.SHIFTS
        assertEquals(20, ks.size)
        assertEquals((0 until 20).map { 17 + 3 * it }, ks)
        assertTrue(ks.all { minOf(it % 150, 150 - it % 150) >= 15 })
    }
}
