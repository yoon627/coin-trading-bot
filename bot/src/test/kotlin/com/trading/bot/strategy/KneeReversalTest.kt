package com.trading.bot.strategy

import com.trading.common.config.TradingProperties
import com.trading.common.domain.Candle
import com.trading.common.strategy.Indicators
import com.trading.common.strategy.KneeReversal
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KneeReversalTest {

    private val strategy = KneeReversal()
    private val config = TradingProperties()

    private fun price(candles: List<Candle>) = candles[0].tradePrice

    /**
     * 각 조건을 전략과 같은 식으로 개별 판정한다. 음성 fixture 가 목표 조건 **하나만** 깨뜨리는지
     * 확인하기 위한 것으로, 여러 조건이 동시에 깨지면 그 조건을 전략에서 지워도 테스트가 통과해
     * 회귀를 놓친다.
     */
    private fun conditions(candles: List<Candle>): Map<String, Boolean> {
        val peak = Indicators.highestHigh(candles, 40)
        val trough = Indicators.lowestLow(candles, 20)
        val current = price(candles)
        val avgVolume = candles.take(10).map { it.candleAccTradeVolume }.average()
        return mapOf(
            "decline" to ((peak - trough) / peak >= 0.15),
            "knee" to ((current - trough) / trough in 0.03..0.12),
            "upbar" to (current > candles[1].tradePrice),
            "rsi" to (Indicators.calculateRsi(candles.take(40), 14) in 35.0..55.0),
            "volume" to (candles[0].candleAccTradeVolume >= avgVolume),
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
    fun `buys at the knee - decline then confirmed rebound`() = runTest {
        val candles = KneeFixtures.reversal()
        assertTrue(strategy.shouldBuy(candles, price(candles), config))
    }

    @Test
    fun `does not buy without a meaningful decline`() = runTest {
        // 고점에서 12% 만 밀린 구간 — 무릎이라 부를 하락이 아니다.
        assertRejectedOnlyBy("decline", KneeFixtures.reversal(bottom = 8_800.0, reboundPct = 0.05, reboundBars = 5))
    }

    @Test
    fun `does not buy when price sits too far above the trough`() = runTest {
        // 아래꼬리로 저점만 낮춰 저점 대비 12% 를 넘긴다 — 무릎이 아니라 이미 허리 위.
        assertRejectedOnlyBy("knee", KneeFixtures.reversal(troughWickFactor = 0.93))
    }

    @Test
    fun `does not buy when the latest bar is down`() = runTest {
        assertRejectedOnlyBy("upbar", KneeFixtures.reversal(lastCloseFactor = 0.99))
    }

    @Test
    fun `does not buy while rsi is still washed out`() = runTest {
        // 짧고 급한 하락 — 위치는 무릎이어도 회복 신호가 없다.
        assertRejectedOnlyBy("rsi", KneeFixtures.reversal(flatBars = 35, dropBars = 4))
    }

    @Test
    fun `does not buy without volume confirmation`() = runTest {
        assertRejectedOnlyBy("volume", KneeFixtures.reversal(lastVolume = 50.0))
    }

    @Test
    fun `does not buy with insufficient candles`() = runTest {
        // 라이브 warm-up 구간(21~39봉): 예외 없이 조용히 false.
        val candles = KneeFixtures.reversal().take(39)
        assertFalse(strategy.shouldBuy(candles, price(candles), config))
    }

    @Test
    fun `does not buy on candles with empty high and low`() = runTest {
        // O/H/L 이 0 이면 (price - 0) / 0 이 Infinity 가 된다 — 명시 가드로 막혀야 한다.
        val candles = KneeFixtures.zeroOhlc()
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

    @Test
    fun `does not sell on a rising bar even if it is inside the band`() = runTest {
        // 밴드가 가격보다 빨리 확장되면 상승 중인 봉도 "상단 안으로 복귀"로 보인다. 어깨는 꺾임이지
        // 상승이 아니다. 이 window(상승 가속 구간)는 하락봉 가드가 없으면 실제로 오발동했던 지점이다.
        val chronological = KneeFixtures.reversalThenRally().reversed()
        val window = chronological.subList(58, 108).reversed()

        assertTrue(window[0].tradePrice > window[1].tradePrice, "상승봉 window 가 아니다")
        assertFalse(strategy.shouldSell(window, window[0].tradePrice, config))
    }

    @Test
    fun `does not sell with insufficient candles`() = runTest {
        val candles = KneeFixtures.overheatFade().take(15)
        assertFalse(strategy.shouldSell(candles, price(candles), config))
    }
}
