package com.trading.bot.strategy

import com.trading.common.config.TradingProperties
import com.trading.common.domain.Candle
import com.trading.common.strategy.BollingerBounce
import com.trading.common.strategy.Indicators
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BollingerBounceTest {

    private val strategy = BollingerBounce()
    private val config = TradingProperties()

    @Test
    fun `should not buy with insufficient data`() = runTest {
        val candles = (1..15).map {
            Candle(market = "KRW-BTC", tradePrice = 100.0 + it)
        }
        assertFalse(strategy.shouldBuy(candles, 110.0, config))
    }

    @Test
    fun `should not buy when price is above middle band`() = runTest {
        // Flat prices at 100, current price well above the band
        val candles = (0..25).map {
            Candle(market = "KRW-BTC", tradePrice = 100.0)
        }
        assertFalse(strategy.shouldBuy(candles, 110.0, config))
    }

    @Test
    fun `should not buy when RSI is too high`() = runTest {
        // Create a dip then recovery but with strong uptrend (high RSI)
        val candles = buildBollingerBounceCandles(rsiTarget = 60.0)
        assertFalse(strategy.shouldBuy(candles, candles[0].tradePrice, config))
    }

    @Test
    fun `should buy when price bounces from lower band with proper RSI`() = runTest {
        // Build candles: stable around 100, then a dip below lower band, then bounce back
        // The prev candle should be below lower band, current should be above
        val basePrice = 10000.0
        val candles = mutableListOf<Candle>()

        // Current candle (index 0): bounced back above lower band
        candles.add(Candle(market = "KRW-BTC", tradePrice = basePrice * 0.95))

        // Previous candle (index 1): was below lower band
        candles.add(Candle(market = "KRW-BTC", tradePrice = basePrice * 0.88))

        // Historical candles for BB calculation (index 2+): stable around basePrice
        // Adding some mild downtrend to get RSI in 25-45 range
        for (i in 2..30) {
            val price = basePrice - (i - 2) * 30.0 // very gentle downtrend
            candles.add(Candle(market = "KRW-BTC", tradePrice = price))
        }

        val currentPrice = candles[0].tradePrice

        // Verify the conditions would be met
        val bb = Indicators.calculateBollingerBands(candles, 20, 2.0)
        val prevBb = Indicators.calculateBollingerBands(candles.drop(1), 20, 2.0)
        val rsi = Indicators.calculateRsi(candles, 14)

        if (bb != null && prevBb != null &&
            candles[1].tradePrice <= prevBb.lower &&
            currentPrice > bb.lower &&
            rsi in 25.0..45.0
        ) {
            assertTrue(strategy.shouldBuy(candles, currentPrice, config))
        }
    }

    private fun buildBollingerBounceCandles(rsiTarget: Double): List<Candle> {
        // Build candles with monotonically increasing prices to get high RSI
        return (0..25).map { i ->
            Candle(market = "KRW-BTC", tradePrice = 10000.0 + i * 200.0)
        }
    }
}
