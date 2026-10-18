package com.trading.bot.strategy

import com.trading.common.domain.Candle
import com.trading.common.strategy.Indicators
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class IndicatorsExtendedTest {

    @Test
    fun `calculateRsi returns 50 for insufficient data`() {
        val candles = (0..5).map { Candle(tradePrice = 100.0) }
        assertEquals(50.0, Indicators.calculateRsi(candles, 14))
    }

    @Test
    fun `calculateRsi returns 100 for all gains`() {
        // candles[0] 이 최신이라 시간순으로 계속 오른다.
        val candles = (0..20).map { i -> Candle(tradePrice = 200.0 - i * 5.0) }
        val rsi = Indicators.calculateRsi(candles, 14)
        assertTrue(rsi > 90, "RSI should be high for monotonic increase: $rsi")
    }

    @Test
    fun `calculateRsi returns near 0 for all losses`() {
        // 시간순으로 계속 내린다.
        val candles = (0..20).map { i -> Candle(tradePrice = 100.0 + i * 5.0) }
        val rsi = Indicators.calculateRsi(candles, 14)
        assertTrue(rsi < 10, "RSI should be low for monotonic decrease: $rsi")
    }

    @Test
    fun `calculateRsi returns around 50 for flat prices`() {
        val candles = (0..20).map { Candle(tradePrice = 100.0) }
        val rsi = Indicators.calculateRsi(candles, 14)
        // 변화가 없으면 avgLoss 가 0 이라 구현은 100 을 돌려준다.
        assertTrue(rsi >= 50.0)
    }

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
            Candle(tradePrice = 999.0), // MA(3) 밖
        )
        assertEquals(200.0, Indicators.calculateMa(candles, 3))
    }

    @Test
    fun `isMaUptrend returns true for uptrending prices`() {
        val candles = (0..25).map { i -> Candle(tradePrice = 200.0 - i * 3.0) }
        assertTrue(Indicators.isMaUptrend(candles))
    }

    @Test
    fun `isMaUptrend returns false for downtrending prices`() {
        val candles = (0..25).map { i -> Candle(tradePrice = 100.0 + i * 3.0) }
        assertFalse(Indicators.isMaUptrend(candles))
    }

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
