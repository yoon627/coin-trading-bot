package com.trading.bot.strategy

import com.trading.bot.config.TradingProperties
import com.trading.bot.domain.Candle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VolatilityBreakoutTest {

    private val strategy = VolatilityBreakout()
    private val config = TradingProperties(kValue = 0.5)

    private fun candle(open: Double, high: Double, low: Double, close: Double) = Candle(
        market = "KRW-BTC",
        openingPrice = open,
        highPrice = high,
        lowPrice = low,
        tradePrice = close,
    )

    @Test
    fun `should buy when price breaks above target`() = runTest {
        val candles = listOf(
            candle(100.0, 130.0, 90.0, 125.0),  // today
            candle(95.0, 120.0, 80.0, 100.0),   // yesterday
        )
        // target = 100 + (120-80)*0.5 = 120, currentPrice = 125 > 120
        assertTrue(strategy.shouldBuy(candles, 125.0, config))
    }

    @Test
    fun `should not buy when price below target`() = runTest {
        val candles = listOf(
            candle(100.0, 110.0, 95.0, 105.0),
            candle(95.0, 120.0, 80.0, 100.0),
        )
        // target = 100 + (120-80)*0.5 = 120, currentPrice = 115 < 120
        assertFalse(strategy.shouldBuy(candles, 115.0, config))
    }

    @Test
    fun `should not buy with insufficient data`() = runTest {
        val candles = listOf(candle(100.0, 110.0, 90.0, 105.0))
        assertFalse(strategy.shouldBuy(candles, 105.0, config))
    }
}
