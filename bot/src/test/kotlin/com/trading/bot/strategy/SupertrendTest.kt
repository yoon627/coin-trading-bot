package com.trading.bot.strategy

import com.trading.common.config.TradingProperties
import com.trading.common.domain.Candle
import com.trading.common.strategy.Supertrend
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SupertrendTest {

    private val strategy = Supertrend()
    private val config = TradingProperties()

    private fun candle(close: Double, high: Double = close + 30.0, low: Double = close - 30.0) = Candle(
        market = "KRW-BTC",
        openingPrice = close,
        highPrice = high,
        lowPrice = low,
        tradePrice = close,
        candleAccTradeVolume = 100.0,
    )

    /** 최신순 하락 시계열 — index 0 이 가장 최근(= 가장 낮은 가격). */
    private fun downtrend(count: Int) = (0 until count).map { candle(10_050.0 + it * 50.0) }

    private val spike = candle(20_000.0, high = 20_000.0, low = 11_900.0)

    @Test
    fun `should not buy with insufficient data`() = runTest {
        assertFalse(strategy.shouldBuy(listOf(spike) + downtrend(39), 20_000.0, config))
    }

    @Test
    fun `should buy when the trend flips from down to up`() = runTest {
        assertTrue(strategy.shouldBuy(listOf(spike) + downtrend(40), 20_000.0, config))
    }

    @Test
    fun `should not buy in a sustained uptrend`() = runTest {
        val candles = (0 until 41).map { candle(20_000.0 - it * 50.0) }
        assertFalse(strategy.shouldBuy(candles, candles[0].tradePrice, config))
    }

    @Test
    fun `should not buy on the bar after the flip`() = runTest {
        // 전환은 직전 봉에서 이미 끝났다 — 추세가 상승으로 유지되기만 하는 봉은 신호가 아니다.
        val candles = listOf(candle(20_500.0), spike) + downtrend(40)
        assertFalse(strategy.shouldBuy(candles, 20_500.0, config))
    }
}
