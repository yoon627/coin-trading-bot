package com.trading.bot.strategy

import com.trading.common.domain.Candle
import com.trading.common.strategy.Indicators
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class IndicatorsTest {

    private fun candle(open: Double, high: Double, low: Double, close: Double) = Candle(
        market = "KRW-BTC",
        openingPrice = open,
        highPrice = high,
        lowPrice = low,
        tradePrice = close,
    )

    @Test
    fun `calculateTargetPrice returns correct target`() {
        val candles = listOf(
            candle(100.0, 110.0, 90.0, 105.0),   // today
            candle(95.0, 120.0, 80.0, 100.0),     // yesterday
        )
        // target = today open + (yesterday high - yesterday low) * k
        // = 100 + (120 - 80) * 0.5 = 100 + 20 = 120
        val target = Indicators.calculateTargetPrice(candles, 0.5)
        assertEquals(120.0, target, 0.001)
    }

    @Test
    fun `calculateTargetPrice with insufficient data returns 0`() {
        val candles = listOf(candle(100.0, 110.0, 90.0, 105.0))
        assertEquals(0.0, Indicators.calculateTargetPrice(candles))
    }

    @Test
    fun `calculateMa returns correct average`() {
        val candles = listOf(
            candle(0.0, 0.0, 0.0, 100.0),
            candle(0.0, 0.0, 0.0, 200.0),
            candle(0.0, 0.0, 0.0, 300.0),
        )
        assertEquals(200.0, Indicators.calculateMa(candles, 3), 0.001)
        assertEquals(150.0, Indicators.calculateMa(candles, 2), 0.001)
    }

    @Test
    fun `calculateMa with insufficient data returns 0`() {
        val candles = listOf(candle(0.0, 0.0, 0.0, 100.0))
        assertEquals(0.0, Indicators.calculateMa(candles, 5))
    }

    @Test
    fun `calculateRsi returns 50 with insufficient data`() {
        val candles = (1..5).map { candle(0.0, 0.0, 0.0, it.toDouble()) }
        assertEquals(50.0, Indicators.calculateRsi(candles, 14))
    }

    @Test
    fun `calculateRsi returns 100 for all gains`() {
        // Monotonically increasing prices (reversed, since candles[0] is most recent)
        val candles = (15 downTo 0).map { candle(0.0, 0.0, 0.0, 100.0 + it.toDouble()) }
        val rsi = Indicators.calculateRsi(candles, 14)
        assertEquals(100.0, rsi, 0.001)
    }

    @Test
    fun `calculateRsi returns near 0 for all losses`() {
        // candles[0] is most recent, prices decreasing over time
        // So most recent has lowest price
        val candles = (0..15).map { candle(0.0, 0.0, 0.0, 100.0 - it.toDouble()) }
        val rsi = Indicators.calculateRsi(candles, 14)
        // When reversed for calculation: prices go from 85 to 100 (all gains), so RSI should be high
        // Let's fix: we want all losses, so most recent should be lowest
        // candles = [100, 99, 98, ... 85] -> reversed = [85, 86, ... 100] -> all gains
        // For all losses: candles = [85, 86, 87, ... 100] -> reversed = [100, 99, ... 85] -> all losses
        val lossCandles = (0..15).map { candle(0.0, 0.0, 0.0, 85.0 + it.toDouble()) }
        val lossRsi = Indicators.calculateRsi(lossCandles, 14)
        assertTrue(lossRsi < 1.0, "RSI should be near 0 for all losses, got $lossRsi")
    }

    @Test
    fun `isMaUptrend returns true when short MA above long MA`() {
        // Recent prices higher -> short MA > long MA
        val candles = (20 downTo 0).map {
            candle(0.0, 0.0, 0.0, 100.0 + it * 2.0)
        }
        assertTrue(Indicators.isMaUptrend(candles, 5, 20))
    }

    @Test
    fun `isMaUptrend returns false when short MA below long MA`() {
        // Recent prices lower -> short MA < long MA
        val candles = (0..20).map {
            candle(0.0, 0.0, 0.0, 100.0 + it * 2.0)
        }
        assertFalse(Indicators.isMaUptrend(candles, 5, 20))
    }

    @Test
    fun `lowestLow returns the lowest low within the recent period`() {
        val candles = listOf(
            candle(0.0, 0.0, 95.0, 0.0),
            candle(0.0, 0.0, 90.0, 0.0),
            candle(0.0, 0.0, 85.0, 0.0),
            candle(0.0, 0.0, 80.0, 0.0), // period 3 이면 제외되는 더 낮은 저가
        )
        assertEquals(85.0, Indicators.lowestLow(candles, 3), 0.001)
        assertEquals(80.0, Indicators.lowestLow(candles, 4), 0.001)
    }

    @Test
    fun `lowestLow returns 0 with insufficient data`() {
        val candles = listOf(candle(0.0, 0.0, 95.0, 0.0))
        assertEquals(0.0, Indicators.lowestLow(candles, 5))
    }

    @Test
    fun `highestHigh returns the highest high within the recent period`() {
        val candles = listOf(
            candle(0.0, 105.0, 0.0, 0.0),
            candle(0.0, 110.0, 0.0, 0.0),
            candle(0.0, 115.0, 0.0, 0.0),
            candle(0.0, 120.0, 0.0, 0.0), // period 3 이면 제외되는 더 높은 고가
        )
        assertEquals(115.0, Indicators.highestHigh(candles, 3), 0.001)
        assertEquals(120.0, Indicators.highestHigh(candles, 4), 0.001)
    }

    @Test
    fun `highestHigh returns 0 with insufficient data`() {
        val candles = listOf(candle(0.0, 105.0, 0.0, 0.0))
        assertEquals(0.0, Indicators.highestHigh(candles, 5))
    }

    @Test
    fun `lowestLow and highestHigh reject non-positive period`() {
        // 이 가드가 없으면 take(0).minOf {} 가 NoSuchElementException 을 던진다.
        val candles = listOf(candle(100.0, 105.0, 95.0, 100.0))
        assertEquals(0.0, Indicators.lowestLow(candles, 0))
        assertEquals(0.0, Indicators.highestHigh(candles, 0))
        assertEquals(0.0, Indicators.lowestLow(candles, -1))
        assertEquals(0.0, Indicators.highestHigh(candles, -1))
    }
}
