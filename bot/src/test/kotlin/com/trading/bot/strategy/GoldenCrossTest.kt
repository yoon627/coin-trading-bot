package com.trading.bot.strategy

import com.trading.bot.config.TradingProperties
import com.trading.bot.domain.Candle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class GoldenCrossTest {

    private val strategy = GoldenCross()
    private val config = TradingProperties()

    @Test
    fun `should not buy with insufficient data`() = runTest {
        val candles = (1..15).map {
            Candle(market = "KRW-BTC", tradePrice = 100.0)
        }
        assertFalse(strategy.shouldBuy(candles, 100.0, config))
    }

    @Test
    fun `should not buy in downtrend`() = runTest {
        // Monotonically decreasing recent prices -> no golden cross
        val candles = (0..25).map {
            Candle(market = "KRW-BTC", tradePrice = 200.0 - it * 3.0)
        }
        assertFalse(strategy.shouldBuy(candles, 200.0, config))
    }
}
