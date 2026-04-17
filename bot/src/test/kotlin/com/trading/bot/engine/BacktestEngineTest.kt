package com.trading.bot.engine

import com.trading.common.config.TradingProperties
import com.trading.common.domain.Candle
import com.trading.common.strategy.TradingStrategy
import com.trading.common.strategy.VolatilityBreakout
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class BacktestEngineTest {

    private val strategy = VolatilityBreakout()
    private val tradingProperties = TradingProperties()
    private val engine = BacktestEngine(listOf(strategy), tradingProperties)

    @Test
    fun `returns null for unknown strategy`() = runTest {
        val candles = buildCandles(100)
        val result = engine.run("nonexistent", candles, "KRW-BTC")
        assertNull(result)
    }

    @Test
    fun `returns null for insufficient candles`() = runTest {
        val candles = buildCandles(30)
        val result = engine.run("volatility_breakout", candles, "KRW-BTC")
        assertNull(result)
    }

    @Test
    fun `runs backtest with sufficient data`() = runTest {
        val candles = buildTrendCandles(120)
        val result = engine.run("volatility_breakout", candles, "KRW-BTC")

        assertNotNull(result)
        result!!
        assertEquals("volatility_breakout", result.strategyName)
        assertEquals("KRW-BTC", result.ticker)
        assertTrue(result.totalTrades >= 0)
        assertEquals(result.winTrades + result.lossTrades, result.totalTrades)
        assertTrue(result.winRate in 0.0..100.0)
        assertTrue(result.maxDrawdownPct >= 0.0)
    }

    @Test
    fun `compareAll runs all strategies`() = runTest {
        val anotherStrategy = object : TradingStrategy {
            override val name = "always_buy"
            override suspend fun shouldBuy(candles: List<Candle>, currentPrice: Double, config: TradingProperties) = true
        }
        val multiEngine = BacktestEngine(listOf(strategy, anotherStrategy), tradingProperties)
        val candles = buildTrendCandles(120)

        val results = multiEngine.compareAll(candles, "KRW-BTC")

        assertEquals(2, results.size)
        assertTrue(results.any { it.strategyName == "volatility_breakout" })
        assertTrue(results.any { it.strategyName == "always_buy" })
    }

    @Test
    fun `backtest respects stop loss`() = runTest {
        // Create a scenario with an initial breakout then crash
        val config = BacktestConfig(maxLossPct = 3.0, takeProfitPct = 50.0, maxHoldDays = 100)
        val candles = buildCrashCandles(120)

        val result = engine.run("volatility_breakout", candles, "KRW-BTC", config)

        if (result != null && result.trades.isNotEmpty()) {
            val stopLossTrades = result.trades.filter { it.reason == "STOP_LOSS" }
            stopLossTrades.forEach { trade ->
                assertTrue(trade.pnlPercent <= 0, "Stop loss trade should have negative PnL")
            }
        }
    }

    @Test
    fun `buy and hold calculation is correct`() = runTest {
        val candles = buildTrendCandles(120)
        val result = engine.run("volatility_breakout", candles, "KRW-BTC")

        assertNotNull(result)
        result!!
        // Buy & hold should reflect the overall trend
        val chronological = candles.reversed()
        val firstPrice = chronological[50].tradePrice
        val lastPrice = chronological.last().tradePrice
        val expectedBuyAndHold = ((lastPrice - firstPrice) / firstPrice) * 100.0
        assertEquals(expectedBuyAndHold, result.buyAndHoldPct, 0.01)
    }

    private fun buildCandles(count: Int): List<Candle> {
        // Flat prices - unlikely to trigger any strategy
        return (0 until count).map {
            Candle(
                market = "KRW-BTC",
                tradePrice = 10000.0,
                openingPrice = 10000.0,
                highPrice = 10000.0,
                lowPrice = 10000.0,
                candleAccTradeVolume = 100.0,
            )
        }
    }

    private fun buildTrendCandles(count: Int): List<Candle> {
        // Uptrend with some volatility to trigger breakout signals
        return (0 until count).map { i ->
            val base = 10000.0 + (count - i) * 50.0
            val volatility = if (i % 3 == 0) 200.0 else -100.0
            Candle(
                market = "KRW-BTC",
                tradePrice = base + volatility,
                openingPrice = base,
                highPrice = base + 300.0,
                lowPrice = base - 150.0,
                candleAccTradeVolume = 100.0 + i * 5.0,
            )
        }
    }

    private fun buildCrashCandles(count: Int): List<Candle> {
        // Initial breakout then sharp decline
        return (0 until count).map { i ->
            val reversedI = count - 1 - i
            val price = when {
                reversedI < 55 -> 10000.0 + reversedI * 30.0  // gentle rise to set up
                reversedI < 65 -> 11650.0 + (reversedI - 55) * 200.0  // breakout
                else -> 13650.0 - (reversedI - 65) * 300.0  // crash
            }
            Candle(
                market = "KRW-BTC",
                tradePrice = price.coerceAtLeast(5000.0),
                openingPrice = price - 50.0,
                highPrice = price + 100.0,
                lowPrice = price - 200.0,
                candleAccTradeVolume = 200.0,
            )
        }
    }
}
