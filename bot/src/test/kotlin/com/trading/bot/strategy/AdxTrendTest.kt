package com.trading.bot.strategy

import com.trading.common.config.TradingProperties
import com.trading.common.domain.Candle
import com.trading.common.strategy.AdxTrend
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AdxTrendTest {

    private val strategy = AdxTrend()
    private val config = TradingProperties()

    /** 최신순 캔들 — [highs] 도 최신순이며 저가는 고가에서 고정폭만큼 뺀다. */
    private fun candles(highs: List<Double>, range: Double = 200.0) = highs.map { high ->
        Candle(
            market = "KRW-BTC",
            openingPrice = high - range / 2,
            highPrice = high,
            lowPrice = high - range,
            tradePrice = high - range / 2,
            candleAccTradeVolume = 100.0,
        )
    }

    /**
     * 최근 [upBars] 봉은 상승, 그 이전은 하락. 하락폭(110)이 상승폭(100)보다 커서
     * 직전 시점(offset 1) 창에선 −DI 가 여전히 우위 → 이번 봉에서 비로소 +DI 상향 교차가 난다.
     */
    private fun crossSeries(size: Int, upBars: Int): List<Candle> {
        val highs = DoubleArray(size)
        highs[size - 1] = 12_000.0
        for (i in size - 2 downTo 0) {
            highs[i] = highs[i + 1] + if (i < upBars) 100.0 else -110.0
        }
        return candles(highs.toList())
    }

    @Test
    fun `should buy on bullish DI crossover with strong trend`() = runTest {
        val series = crossSeries(size = 40, upBars = 8)
        assertTrue(strategy.shouldBuy(series, series[0].tradePrice, config))
    }

    @Test
    fun `should not buy in monotonic downtrend`() = runTest {
        val highs = (0 until 40).map { 12_000.0 - it * 100.0 }.reversed()
        val series = candles(highs)
        assertFalse(strategy.shouldBuy(series, series[0].tradePrice, config))
    }

    @Test
    fun `should not buy with insufficient candles`() = runTest {
        val series = crossSeries(size = 40, upBars = 8).take(strategy.minCandles - 1)
        assertFalse(strategy.shouldBuy(series, series[0].tradePrice, config))
    }

    @Test
    fun `should signal at exactly the declared minimum`() = runTest {
        val series = crossSeries(size = strategy.minCandles, upBars = 8)
        assertTrue(strategy.shouldBuy(series, series[0].tradePrice, config))
    }

    @Test
    fun `should not buy when DI crosses but ADX is below threshold`() = runTest {
        // 고가·저가가 모두 같은 평탄 구간 → DM 이 전부 0 이라 방향성 없음.
        // 최신 봉 하나만 위로 벌어지면 +DI 교차는 나지만 ADX 는 100/14 ≈ 7 로 20 미만이다.
        val highs = MutableList(40) { 10_000.0 }
        highs[0] = 10_100.0
        val series = candles(highs)
        assertFalse(strategy.shouldBuy(series, series[0].tradePrice, config))
    }
}
