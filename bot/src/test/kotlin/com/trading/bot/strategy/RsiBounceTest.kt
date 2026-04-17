package com.trading.bot.strategy

import com.trading.common.config.TradingProperties
import com.trading.common.domain.Candle
import com.trading.common.strategy.RsiBounce
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class RsiBounceTest {

    private val strategy = RsiBounce()
    private val config = TradingProperties()

    @Test
    fun `should not buy with insufficient data`() = runTest {
        val candles = (1..10).map {
            Candle(market = "KRW-BTC", tradePrice = 100.0 + it)
        }
        assertFalse(strategy.shouldBuy(candles, 110.0, config))
    }

    @Test
    fun `should not buy when RSI is high`() = runTest {
        // Monotonically increasing prices -> high RSI
        val candles = (16 downTo 0).map {
            Candle(market = "KRW-BTC", tradePrice = 100.0 + it * 5.0)
        }
        assertFalse(strategy.shouldBuy(candles, 180.0, config))
    }
}
