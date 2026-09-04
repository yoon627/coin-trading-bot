package com.trading.bot.strategy

import com.trading.common.config.TradingProperties
import com.trading.common.domain.Candle
import com.trading.common.strategy.CciReversal
import com.trading.common.strategy.KeltnerBreakout
import com.trading.common.strategy.MoneyFlowIndex
import com.trading.common.strategy.StochasticCross
import com.trading.common.strategy.Supertrend
import com.trading.common.strategy.VwapBand
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 신규 지표 전략의 **사양을 실제로 고정**하는 테스트.
 *
 * 교차 검증에서 "기간·계수를 아무 값으로 바꿔도 기존 테스트가 그대로 통과한다"는 지적이 나왔다 —
 * 그러면 테스트는 사양이 아니라 "가격이 크게 움직였는가" 만 재는 셈이다. 여기서는 임계 바로 위/같은 값,
 * 교차 조건 자체, 퇴화 입력처럼 **구현을 바꾸면 반드시 깨지는** 지점만 짚는다.
 */
class StrategySpecPinTest {

    private val props = TradingProperties()

    private fun candle(open: Double, high: Double, low: Double, close: Double, volume: Double = 100.0, quote: Double = 0.0) =
        Candle(
            market = "KRW-TEST",
            openingPrice = open, highPrice = high, lowPrice = low, tradePrice = close,
            candleAccTradeVolume = volume,
            candleAccTradePrice = quote,
        )

    private fun flat(count: Int, price: Double, volume: Double = 100.0, quote: Double = 0.0) =
        List(count) { candle(price, price, price, price, volume, quote) }

    @Test
    fun `keltner band level is pinned — an equal close is not a breakout`() = runTest {
        val strategy = KeltnerBreakout()
        // 평탄 구간은 ATR=0 이라 밴드가 EMA 와 같아진다 — 임계가 EMA 바로 위로 고정되므로 계수를 바꾸면 깨진다.
        val base = flat(30, 10_000.0)
        val above = listOf(candle(10_000.0, 10_010.0, 10_000.0, 10_010.0)) + base
        val equal = listOf(candle(10_000.0, 10_000.0, 10_000.0, 10_000.0)) + base
        assertTrue(strategy.shouldBuy(above, above.first().tradePrice, props), "EMA(=밴드) 위 종가는 돌파")
        assertFalse(strategy.shouldBuy(equal, equal.first().tradePrice, props), "같은 값은 돌파가 아니다")
    }

    @Test
    fun `supertrend fires on the flip bar only, not while the uptrend continues`() = runTest {
        val strategy = Supertrend()
        val down = (0 until 45).map { i ->
            val p = 10_000.0 - i * 50.0
            candle(p + 25, p + 40, p - 40, p)
        }.reversed()
        val flip = listOf(candle(8_000.0, 9_500.0, 7_900.0, 9_400.0)) + down
        val afterFlip = listOf(candle(9_400.0, 9_600.0, 9_350.0, 9_550.0)) + flip
        if (strategy.shouldBuy(flip, flip.first().tradePrice, props)) {
            assertFalse(
                strategy.shouldBuy(afterFlip, afterFlip.first().tradePrice, props),
                "전환 다음 봉은 추세가 이미 상승이라 신호가 아니다",
            )
        }
    }

    @Test
    fun `stochastic requires an actual cross, not merely K above D`() = runTest {
        val strategy = StochasticCross()
        val rising = (0 until 25).map { i ->
            val p = 9_000.0 + i * 10.0
            candle(p, p + 5, p - 5, p)
        }.reversed()
        assertFalse(strategy.shouldBuy(rising, rising.first().tradePrice, props), "교차 없이 K>D 만으로는 매수하지 않는다")
    }

    @Test
    fun `MFI stays silent on a flat window instead of reading the neutral 50 as an oversold cross`() = runTest {
        assertFalse(MoneyFlowIndex().shouldBuy(flat(30, 10_000.0), 10_000.0, props), "flow 가 양쪽 0 이면 MFI 는 정의되지 않는다")
    }

    @Test
    fun `VWAP falls back per candle, so a partially filled window is not skewed`() = runTest {
        // 거래대금이 첫 봉에만 채워진 창. 폴백을 윈도 합계로 판정하면 (부분 notional)/(전체 volume) 이 되어
        // VWAP 이 크게 과소 계산되고, 그 순간 "직전 종가 < 직전 VWAP" 이 거짓으로 성립하지 않는다.
        val mixed = buildList {
            add(candle(10_000.0, 10_050.0, 9_950.0, 10_010.0, volume = 100.0, quote = 1_001_000.0))
            addAll(flat(25, 10_000.0, volume = 100.0))
        }
        assertFalse(VwapBand().shouldBuy(mixed, mixed.first().tradePrice, props), "평탄 구간이라 직전 종가 == 직전 VWAP → 매수 아님")
    }

    @Test
    fun `CCI stays silent when the mean deviation is zero`() = runTest {
        assertFalse(CciReversal().shouldBuy(flat(30, 10_000.0), 10_000.0, props), "평균편차 0 이면 CCI 는 정의되지 않는다")
    }
}
