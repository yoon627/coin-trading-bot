package com.trading.bot.strategy

import com.trading.common.config.TradingProperties
import com.trading.common.domain.Candle
import com.trading.common.strategy.KeltnerBreakout
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KeltnerBreakoutTest {

    private val strategy = KeltnerBreakout()
    private val config = TradingProperties()

    private fun candle(close: Double, high: Double, low: Double) = Candle(
        market = "KRW-BTC",
        openingPrice = close,
        highPrice = high,
        lowPrice = low,
        tradePrice = close,
        candleAccTradeVolume = 100.0,
    )

    private fun flat(count: Int) = List(count) { candle(10_000.0, 10_100.0, 9_900.0) }

    private val breakout = candle(12_000.0, 12_000.0, 9_900.0)

    @Test
    fun `should not buy with insufficient data`() = runTest {
        val candles = listOf(breakout) + flat(19)
        assertFalse(strategy.shouldBuy(candles, 12_000.0, config))
    }

    @Test
    fun `should buy when close breaks above the upper band`() = runTest {
        val candles = listOf(breakout) + flat(20)
        assertTrue(strategy.shouldBuy(candles, 12_000.0, config))
    }

    @Test
    fun `should not buy while price stays inside the channel`() = runTest {
        assertFalse(strategy.shouldBuy(flat(21), 10_000.0, config))
    }

    @Test
    fun `should not buy on the bar after a breakout`() = runTest {
        // 직전 봉이 이미 밴드 위 — 돌파는 그 봉에서 끝났고 여기서 다시 신호가 나면 중복 진입이다.
        val candles = listOf(candle(12_500.0, 12_600.0, 12_400.0), breakout) + flat(19)
        assertFalse(strategy.shouldBuy(candles, 12_500.0, config))
    }
}
