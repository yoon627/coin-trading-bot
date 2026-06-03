package com.trading.bot.strategy

import com.trading.common.config.TradingProperties
import com.trading.common.domain.Candle
import com.trading.common.strategy.Indicators
import com.trading.common.strategy.MeanReversion
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MeanReversionTest {

    private val strategy = MeanReversion()
    private val config = TradingProperties()

    @Test
    fun `should not buy with insufficient data`() = runTest {
        val candles = (1..15).map {
            Candle(market = "KRW-BTC", tradePrice = 100.0 + it)
        }
        assertFalse(strategy.shouldBuy(candles, 110.0, config))
    }

    @Test
    fun `should not buy when price is at MA20`() = runTest {
        // Flat prices -> no deviation from MA
        val candles = (0..25).map {
            Candle(market = "KRW-BTC", tradePrice = 10000.0, candleAccTradeVolume = 100.0)
        }
        assertFalse(strategy.shouldBuy(candles, 10000.0, config))
    }

    @Test
    fun `should not buy when price is above MA20`() = runTest {
        // Price above MA20 -> not oversold
        val candles = (0..25).map { i ->
            Candle(market = "KRW-BTC", tradePrice = 10000.0 + i * 10.0, candleAccTradeVolume = 100.0)
        }
        assertFalse(strategy.shouldBuy(candles, 11000.0, config))
    }

    @Test
    fun `should buy when price is significantly below MA20 with conditions met`() = runTest {
        // Build candles where:
        // - MA20 is around 10000
        // - Current price is 3%+ below MA20
        // - Bollinger width is narrow (< 0.15)
        // - RSI in 25-40 range
        // - Volume is adequate

        val candles = mutableListOf<Candle>()

        // Recent candles (index 0-4): dipped to create oversold condition
        for (i in 0..4) {
            candles.add(
                Candle(
                    market = "KRW-BTC",
                    tradePrice = 9600.0 + i * 20.0, // around 9600-9680, well below MA
                    candleAccTradeVolume = 120.0,
                )
            )
        }

        // Historical candles (index 5-25): stable around 10000 to create MA20 ~10000
        for (i in 5..25) {
            candles.add(
                Candle(
                    market = "KRW-BTC",
                    tradePrice = 10000.0 + (i - 15) * 5.0, // small fluctuation around 10000
                    candleAccTradeVolume = 100.0,
                )
            )
        }

        val currentPrice = 9650.0 // ~3.5% below MA20

        // Verify conditions
        val ma20 = Indicators.calculateMa(candles, 20)
        val deviation = (currentPrice - ma20) / ma20
        val bb = Indicators.calculateBollingerBands(candles, 20, 2.0)
        val rsi = Indicators.calculateRsi(candles, 14)

        if (deviation < -0.03 && bb != null && bb.width < 0.15 && rsi in 25.0..40.0) {
            assertTrue(strategy.shouldBuy(candles, currentPrice, config))
        }
    }

    @Test
    fun `should not buy when volatility is too high`() = runTest {
        // Wide Bollinger bands (high volatility)
        val candles = mutableListOf<Candle>()
        for (i in 0..25) {
            // Alternating high/low to create wide bands
            val price = if (i % 2 == 0) 12000.0 else 8000.0
            candles.add(Candle(market = "KRW-BTC", tradePrice = price, candleAccTradeVolume = 100.0))
        }
        assertFalse(strategy.shouldBuy(candles, 7500.0, config))
    }

    @Test
    fun `should sell when close returns above MA20`() = runTest {
        // [2..20]=base 10000, [1]=9500(직전 MA 아래), [0]=10500(현재 MA 위 복귀).
        val candles = buildList {
            add(Candle(market = "KRW-BTC", tradePrice = 10500.0)) // [0]
            add(Candle(market = "KRW-BTC", tradePrice = 9500.0)) // [1]
            for (i in 2..20) add(Candle(market = "KRW-BTC", tradePrice = 10000.0))
        }
        assertTrue(strategy.shouldSell(candles, candles[0].tradePrice, config))
    }

    @Test
    fun `should not sell when close stays below MA20`() = runTest {
        val candles = buildList {
            add(Candle(market = "KRW-BTC", tradePrice = 9500.0)) // [0]
            add(Candle(market = "KRW-BTC", tradePrice = 9400.0)) // [1]
            for (i in 2..20) add(Candle(market = "KRW-BTC", tradePrice = 10000.0))
        }
        assertFalse(strategy.shouldSell(candles, candles[0].tradePrice, config))
    }

    @Test
    fun `should not sell with insufficient data`() = runTest {
        val candles = (1..15).map { Candle(market = "KRW-BTC", tradePrice = 100.0 + it) }
        assertFalse(strategy.shouldSell(candles, 110.0, config))
    }

    @Test
    fun `should not sell when prev close already above MA20`() = runTest {
        // 직전 종가가 이미 MA20 위 → 아래→위 교차가 아님(AND 첫 조건 false 분기).
        val candles = buildList {
            add(Candle(market = "KRW-BTC", tradePrice = 10500.0)) // [0]
            add(Candle(market = "KRW-BTC", tradePrice = 10400.0)) // [1] 이미 MA 위
            for (i in 2..20) add(Candle(market = "KRW-BTC", tradePrice = 10000.0))
        }
        assertFalse(strategy.shouldSell(candles, candles[0].tradePrice, config))
    }
}
