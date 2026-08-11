package com.trading.bot.strategy

import com.trading.common.config.TradingProperties
import com.trading.common.domain.Candle
import com.trading.common.strategy.Indicators
import com.trading.common.strategy.KneePullback
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KneePullbackTest {

    private val strategy = KneePullback()
    private val config = TradingProperties()

    private fun price(candles: List<Candle>) = candles[0].tradePrice

    /** 음성 fixture 가 목표 조건 하나만 깨뜨리는지 확인한다 — [KneeReversalTest] 와 같은 이유. */
    private fun conditions(candles: List<Candle>): Map<String, Boolean> {
        val shortMa = Indicators.calculateMa(candles, 20)
        val longMa = Indicators.calculateMa(candles, 40)
        val current = price(candles)
        return mapOf(
            "trend" to (shortMa > longMa),
            "dip" to (current in shortMa * 0.97..shortMa * 1.02),
            "upbar" to (current > candles[1].tradePrice),
            "bull" to (current > candles[0].openingPrice),
            "rsi" to (Indicators.calculateRsi(candles, 14) in 40.0..60.0),
        )
    }

    private suspend fun assertRejectedOnlyBy(target: String, candles: List<Candle>) {
        val cond = conditions(candles)
        assertFalse(cond.getValue(target), "$target 조건이 깨지지 않은 fixture 다: $cond")
        cond.filterKeys { it != target }.forEach { (name, holds) ->
            assertTrue(holds, "$target 만 깨뜨려야 하는데 $name 도 함께 깨졌다: $cond")
        }
        assertFalse(strategy.shouldBuy(candles, price(candles), config))
    }

    @Test
    fun `buys the pullback - uptrend dipping to ma20 then turning up`() = runTest {
        val candles = KneeFixtures.pullback()
        assertTrue(strategy.shouldBuy(candles, price(candles), config))
    }

    @Test
    fun `does not buy in a downtrend`() = runTest {
        assertRejectedOnlyBy("trend", KneeFixtures.pullback(rise = -0.001, dipPct = 0.0))
    }

    @Test
    fun `does not buy when price is extended above ma20`() = runTest {
        // 조정 없이 한 봉에 4% 올라 20일선 위로 이탈 — 눌린 적이 없으니 무릎이 아니다.
        assertRejectedOnlyBy(
            "dip",
            KneeFixtures.pullback(rise = 0.002, dipBars = 1, dipPct = 0.0, reboundPct = 0.04, sawPct = 0.04, sawPeriod = 3),
        )
    }

    @Test
    fun `does not buy when the latest close is below the previous one`() = runTest {
        // 갭하락 후 양봉 — 봉 자체는 양봉이어도 직전 종가를 못 넘었다.
        assertRejectedOnlyBy("upbar", KneeFixtures.pullback(lastCloseFactor = 0.99, lastOpenFactor = 0.97))
    }

    @Test
    fun `does not buy when the latest bar is bearish`() = runTest {
        assertRejectedOnlyBy("bull", KneeFixtures.pullback(lastOpenFactor = 1.02))
    }

    @Test
    fun `does not buy when rsi is out of the healthy band`() = runTest {
        // 반복적으로 되밀린 추세 — 구조는 눌림목이지만 모멘텀이 죽어 있다.
        assertRejectedOnlyBy(
            "rsi",
            KneeFixtures.pullback(rise = 0.008, dipPct = 0.03, dipBars = 6, sawPct = 0.03, sawPeriod = 4),
        )
    }

    @Test
    fun `does not buy with insufficient candles`() = runTest {
        // ma40 은 40봉이 필요하고 calculateMa 는 부족하면 조용히 0.0 을 준다 — 41봉 미만은 차단.
        val candles = KneeFixtures.pullback().take(40)
        assertFalse(strategy.shouldBuy(candles, price(candles), config))
    }

    @Test
    fun `sells at the shoulder when rsi rolls over from overheated`() = runTest {
        val candles = KneeFixtures.overheatFade()
        assertTrue(strategy.shouldSell(candles, price(candles), config))
    }

    @Test
    fun `sells at the shoulder when a down bar falls back inside the upper band`() = runTest {
        val candles = KneeFixtures.bandReturn()
        assertTrue(strategy.shouldSell(candles, price(candles), config))
    }

    @Test
    fun `does not sell while the move is still intact`() = runTest {
        val candles = KneeFixtures.zigzagFlat()
        assertFalse(strategy.shouldSell(candles, price(candles), config))
    }
}
