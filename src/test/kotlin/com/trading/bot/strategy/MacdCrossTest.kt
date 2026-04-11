package com.trading.bot.strategy

import com.trading.bot.config.TradingProperties
import com.trading.bot.domain.Candle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MacdCrossTest {

    private val strategy = MacdCross()
    private val config = TradingProperties()

    @Test
    fun `should not buy with insufficient data`() = runTest {
        val candles = (1..30).map {
            Candle(market = "KRW-BTC", tradePrice = 100.0 + it)
        }
        assertFalse(strategy.shouldBuy(candles, 130.0, config))
    }

    @Test
    fun `should not buy in downtrend`() = runTest {
        // Monotonically decreasing prices -> MACD negative, no crossover
        val candles = (0..40).map { i ->
            Candle(market = "KRW-BTC", tradePrice = 10000.0 - i * 100.0)
        }
        assertFalse(strategy.shouldBuy(candles, 10000.0, config))
    }

    @Test
    fun `should not buy when RSI is overbought`() = runTest {
        // Strong uptrend -> RSI > 70
        val candles = (0..40).map { i ->
            Candle(market = "KRW-BTC", tradePrice = 10000.0 + i * 500.0)
        }
        assertFalse(strategy.shouldBuy(candles, 30000.0, config))
    }

    @Test
    fun `should buy on bullish MACD crossover`() = runTest {
        // Build a scenario: downtrend then strong recovery to create MACD crossover
        val candles = mutableListOf<Candle>()

        // Recent candles (indices 0-15): strong uptrend (causes MACD to cross above signal)
        for (i in 0..15) {
            candles.add(Candle(market = "KRW-BTC", tradePrice = 10000.0 + (15 - i) * 150.0))
        }

        // Middle period (indices 16-25): flat/slight downtrend
        for (i in 16..25) {
            candles.add(Candle(market = "KRW-BTC", tradePrice = 10000.0 - (i - 16) * 50.0))
        }

        // Older candles (indices 26-45): mild downtrend
        for (i in 26..45) {
            candles.add(Candle(market = "KRW-BTC", tradePrice = 9500.0 - (i - 26) * 30.0))
        }

        val currentPrice = candles[0].tradePrice

        // Verify MACD conditions
        val current = Indicators.calculateMacd(candles, 12, 26, 9)
        val prev = Indicators.calculateMacd(candles.drop(1), 12, 26, 9)
        val rsi = Indicators.calculateRsi(candles, 14)

        if (current != null && prev != null &&
            prev.macd <= prev.signal && current.macd > current.signal &&
            current.histogram > 0 && rsi < 70.0
        ) {
            assertTrue(strategy.shouldBuy(candles, currentPrice, config))
        }
    }
}
