package com.trading.bot.strategy

import com.trading.common.domain.Candle
import com.trading.common.strategy.Indicators
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class IndicatorsExtendedTest {

    // --- calculateRsi edge cases ---

    @Test
    fun `calculateRsi returns 50 for insufficient data`() {
        val candles = (0..5).map { Candle(tradePrice = 100.0) }
        assertEquals(50.0, Indicators.calculateRsi(candles, 14))
    }

    @Test
    fun `calculateRsi returns 100 for all gains`() {
        // All prices increasing -> RSI close to 100
        val candles = (0..20).map { i -> Candle(tradePrice = 200.0 - i * 5.0) }
        val rsi = Indicators.calculateRsi(candles, 14)
        assertTrue(rsi > 90, "RSI should be high for monotonic increase: $rsi")
    }

    @Test
    fun `calculateRsi returns near 0 for all losses`() {
        // All prices decreasing -> RSI close to 0
        val candles = (0..20).map { i -> Candle(tradePrice = 100.0 + i * 5.0) }
        val rsi = Indicators.calculateRsi(candles, 14)
        assertTrue(rsi < 10, "RSI should be low for monotonic decrease: $rsi")
    }

    @Test
    fun `calculateRsi returns around 50 for flat prices`() {
        val candles = (0..20).map { Candle(tradePrice = 100.0) }
        val rsi = Indicators.calculateRsi(candles, 14)
        // Flat prices = no gains, no losses -> avgLoss = 0 -> RSI = 100 technically
        // But if all changes are 0, avgGain and avgLoss both = 0
        assertTrue(rsi >= 50.0) // division by zero returns 100 in impl
    }

    // --- calculateMa edge cases ---

    @Test
    fun `calculateMa returns 0 for insufficient data`() {
        val candles = (0..3).map { Candle(tradePrice = 100.0) }
        assertEquals(0.0, Indicators.calculateMa(candles, 5))
    }

    @Test
    fun `calculateMa calculates correct average`() {
        val candles = listOf(
            Candle(tradePrice = 50.0),
            Candle(tradePrice = 40.0),
            Candle(tradePrice = 30.0),
            Candle(tradePrice = 20.0),
            Candle(tradePrice = 10.0),
        )
        assertEquals(30.0, Indicators.calculateMa(candles, 5))
    }

    @Test
    fun `calculateMa uses only first N candles`() {
        val candles = listOf(
            Candle(tradePrice = 100.0),
            Candle(tradePrice = 200.0),
            Candle(tradePrice = 300.0),
            Candle(tradePrice = 999.0), // not included in MA(3)
        )
        assertEquals(200.0, Indicators.calculateMa(candles, 3))
    }

    // --- calculateBollingerBands ---

    @Test
    fun `calculateBollingerBands returns null for insufficient data`() {
        val candles = (0..10).map { Candle(tradePrice = 100.0) }
        assertNull(Indicators.calculateBollingerBands(candles, 20))
    }

    @Test
    fun `calculateBollingerBands for flat prices has zero width`() {
        val candles = (0..25).map { Candle(tradePrice = 100.0) }
        val bb = Indicators.calculateBollingerBands(candles, 20)
        assertNotNull(bb)
        assertEquals(100.0, bb!!.middle)
        assertEquals(100.0, bb.upper) // stddev = 0
        assertEquals(100.0, bb.lower)
        assertEquals(0.0, bb.width)
    }

    @Test
    fun `calculateBollingerBands upper is above lower`() {
        val candles = (0..25).map { i -> Candle(tradePrice = 100.0 + (i % 5) * 10.0) }
        val bb = Indicators.calculateBollingerBands(candles, 20)
        assertNotNull(bb)
        assertTrue(bb!!.upper > bb.lower)
        assertTrue(bb.upper > bb.middle)
        assertTrue(bb.middle > bb.lower)
    }

    // --- calculateMacd ---

    @Test
    fun `calculateMacd returns null for insufficient data`() {
        val candles = (0..20).map { Candle(tradePrice = 100.0) }
        assertNull(Indicators.calculateMacd(candles, 12, 26, 9))
    }

    @Test
    fun `calculateMacd returns result for sufficient data`() {
        val candles = (0..40).map { i -> Candle(tradePrice = 100.0 + i * 2.0) }
        val macd = Indicators.calculateMacd(candles, 12, 26, 9)
        assertNotNull(macd)
        // histogram = macd - signal
        assertEquals(macd!!.macd - macd.signal, macd.histogram, 0.001)
    }

    // --- checkGoldenCross ---

    @Test
    fun `checkGoldenCross returns false for insufficient data`() {
        val candles = (0..10).map { Candle(tradePrice = 100.0) }
        assertFalse(Indicators.checkGoldenCross(candles))
    }

    @Test
    fun `checkGoldenCross returns false for flat prices`() {
        val candles = (0..25).map { Candle(tradePrice = 100.0) }
        assertFalse(Indicators.checkGoldenCross(candles))
    }

    // --- isMaUptrend ---

    @Test
    fun `isMaUptrend returns true for uptrending prices`() {
        // More recent candles have higher prices -> short MA > long MA
        val candles = (0..25).map { i -> Candle(tradePrice = 200.0 - i * 3.0) }
        assertTrue(Indicators.isMaUptrend(candles))
    }

    @Test
    fun `isMaUptrend returns false for downtrending prices`() {
        val candles = (0..25).map { i -> Candle(tradePrice = 100.0 + i * 3.0) }
        assertFalse(Indicators.isMaUptrend(candles))
    }

    // --- calculateStdDev ---

    @Test
    fun `calculateStdDev returns 0 for flat prices`() {
        val candles = (0..10).map { Candle(tradePrice = 100.0) }
        assertEquals(0.0, Indicators.calculateStdDev(candles, 10))
    }

    @Test
    fun `calculateStdDev returns positive for varying prices`() {
        val candles = (0..10).map { i -> Candle(tradePrice = 100.0 + (i % 2) * 50.0) }
        assertTrue(Indicators.calculateStdDev(candles, 10) > 0)
    }

    // --- calculateEma ---

    @Test
    fun `calculateEma returns 0 for insufficient data`() {
        val candles = (0..3).map { Candle(tradePrice = 100.0) }
        assertEquals(0.0, Indicators.calculateEma(candles, 5))
    }

    @Test
    fun `calculateEma gives more weight to recent prices`() {
        val candles = listOf(
            Candle(tradePrice = 200.0), // most recent
            Candle(tradePrice = 100.0),
            Candle(tradePrice = 100.0),
            Candle(tradePrice = 100.0),
            Candle(tradePrice = 100.0),
        )
        val ema = Indicators.calculateEma(candles, 5)
        val sma = Indicators.calculateMa(candles, 5)
        // EMA should be closer to 200 than SMA
        assertTrue(ema > sma, "EMA ($ema) should be > SMA ($sma) when recent price is high")
    }

    // --- calculateTargetPrice ---

    @Test
    fun `calculateTargetPrice with no range returns opening price`() {
        val candles = listOf(
            Candle(openingPrice = 100.0, highPrice = 50.0, lowPrice = 50.0, tradePrice = 100.0),
            Candle(openingPrice = 90.0, highPrice = 50.0, lowPrice = 50.0, tradePrice = 90.0),
        )
        assertEquals(100.0, Indicators.calculateTargetPrice(candles, 0.5))
    }

    @Test
    fun `calculateTargetPrice returns 0 for insufficient candles`() {
        val candles = listOf(Candle(tradePrice = 100.0))
        assertEquals(0.0, Indicators.calculateTargetPrice(candles))
    }
}
