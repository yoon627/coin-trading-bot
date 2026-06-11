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

    // 손익% 안전망을 넓게 둬 chartExit(데드크로스)만 트리거되게.
    private fun wideStopConfig(chartExit: Boolean) = BacktestConfig(
        maxLossPct = 99.0,
        takeProfitPct = 99.0,
        trailingStopPct = 99.0,
        maxHoldDays = 999,
        chartExitEnabled = chartExit,
    )

    private fun alwaysBuyStrategy() = object : TradingStrategy {
        override val name = "always_buy"
        override suspend fun shouldBuy(candles: List<Candle>, currentPrice: Double, config: TradingProperties) = true
        // shouldSell 은 default(데드크로스 5/20)
    }

    // chronological(과거→최신): 전반 완만 상승(골든) → 후반 급락 → 5/20 데드크로스 교차.
    private fun buildDeadCrossScenario(count: Int): List<Candle> {
        val riseEnd = count - 20
        return (0 until count).map { i ->
            val chronoIdx = count - 1 - i // 0=과거, count-1=최신 (입력은 최신순)
            val price = if (chronoIdx < riseEnd) {
                10000.0 + chronoIdx * 50.0
            } else {
                val peak = 10000.0 + (riseEnd - 1) * 50.0
                peak - (chronoIdx - riseEnd + 1) * 500.0
            }
            Candle(
                market = "KRW-BTC",
                tradePrice = price.coerceAtLeast(1000.0),
                openingPrice = price,
                highPrice = price + 100.0,
                lowPrice = price - 100.0,
                candleAccTradeVolume = 100.0,
            )
        }
    }

    // --- #27 정합: 디폴트 parity / TIME_EXIT / trailing arm ---

    @Test
    fun `config defaults match live trading defaults`() {
        // 백테 디폴트 ≠ 라이브 디폴트가 #27 부정합의 근본 원인 — drift 를 CI 로 가드.
        // kValue·investRatio 는 엔진이 읽지 않는 dead field 라 제외.
        val live = TradingProperties()
        val bt = BacktestConfig()
        assertEquals(live.takeProfitPct, bt.takeProfitPct)
        assertEquals(live.maxLossPct, bt.maxLossPct)
        assertEquals(live.trailingStopPct, bt.trailingStopPct)
        assertEquals(live.trailingArmPct, bt.trailingArmPct)
        assertEquals(live.maxHoldDays, bt.maxHoldDays)
        assertEquals(live.chartExitEnabled, bt.chartExitEnabled)
        assertEquals(live.roundTripFeeRate, bt.feeRate * 2, 1e-12) // 편도 vs 왕복 표현 차이
        assertFalse(bt.useMarketFilter) // 라이브 매수 경로에 MA50 필터 없음
    }

    @Test
    fun `backtest exits by TIME_EXIT after maxHoldDays 1`() = runTest {
        val ce = BacktestEngine(listOf(alwaysBuyStrategy()), tradingProperties)
        val config = BacktestConfig(
            maxLossPct = 99.0, takeProfitPct = 99.0, trailingStopPct = 99.0,
            maxHoldDays = 1, useMarketFilter = false,
        )
        val result = ce.run("always_buy", buildCandles(120), "KRW-BTC", config)

        assertNotNull(result)
        assertTrue(result!!.totalTrades > 0, "scenario must produce trades")
        val timeExits = result.trades.filter { it.reason == "TIME_EXIT" }
        assertTrue(timeExits.isNotEmpty(), "expected TIME_EXIT trades")
        timeExits.forEach { assertEquals(1, it.holdDays) }
    }

    // chronological: 50봉 워밍업(10000) → 진입 → +3% 고점 → 2.5% drop(pnl +0.4%) → 횡보.
    private fun buildArmScenario(count: Int): List<Candle> {
        return (0 until count).map { i ->
            val c = count - 1 - i // chronological index (0=과거)
            val (open, close) = when {
                c <= 50 -> 10000.0 to 10000.0
                c == 51 -> 10000.0 to 10300.0 // fill 봉: +3% 고점 형성
                c == 52 -> 10300.0 to 10040.0 // drop 2.52% from peak, pnl +0.4%
                else -> 10040.0 to 10040.0
            }
            Candle(
                market = "KRW-BTC",
                tradePrice = close,
                openingPrice = open,
                highPrice = maxOf(open, close),
                lowPrice = minOf(open, close),
                candleAccTradeVolume = 100.0,
            )
        }
    }

    @Test
    fun `backtest trailing stop fires with arm zero`() = runTest {
        val ce = BacktestEngine(listOf(alwaysBuyStrategy()), tradingProperties)
        val config = BacktestConfig(
            maxLossPct = 99.0, takeProfitPct = 99.0, trailingStopPct = 2.0, trailingArmPct = 0.0,
            maxHoldDays = 999, useMarketFilter = false,
        )
        val result = ce.run("always_buy", buildArmScenario(120), "KRW-BTC", config)

        assertNotNull(result)
        assertTrue(result!!.trades.any { it.reason == "TRAILING_STOP" }, "arm=0 must fire trailing stop")
    }

    @Test
    fun `backtest trailing stop respects arm threshold`() = runTest {
        val ce = BacktestEngine(listOf(alwaysBuyStrategy()), tradingProperties)
        val config = BacktestConfig(
            maxLossPct = 99.0, takeProfitPct = 99.0, trailingStopPct = 2.0, trailingArmPct = 5.0,
            maxHoldDays = 999, useMarketFilter = false,
        )
        val result = ce.run("always_buy", buildArmScenario(120), "KRW-BTC", config)

        assertNotNull(result)
        assertTrue(result!!.totalTrades > 0, "scenario must produce trades")
        assertTrue(result.trades.none { it.reason == "TRAILING_STOP" }, "peak +3% < arm 5% must not fire")
    }

    @Test
    fun `backtest triggers CHART_EXIT when enabled and dead cross occurs`() = runTest {
        val ce = BacktestEngine(listOf(alwaysBuyStrategy()), tradingProperties)
        val result = ce.run("always_buy", buildDeadCrossScenario(120), "KRW-BTC", wideStopConfig(chartExit = true))

        assertNotNull(result)
        assertTrue(result!!.trades.any { it.reason == "CHART_EXIT" }, "expected a CHART_EXIT trade")
    }

    @Test
    fun `backtest has no CHART_EXIT when disabled`() = runTest {
        val ce = BacktestEngine(listOf(alwaysBuyStrategy()), tradingProperties)
        val result = ce.run("always_buy", buildDeadCrossScenario(120), "KRW-BTC", wideStopConfig(chartExit = false))

        assertNotNull(result)
        // trade 가 0건이면 none 단언이 공허참이 되므로 진입이 실제로 일어났음을 함께 보장.
        assertTrue(result!!.totalTrades > 0, "scenario must produce trades")
        assertTrue(result.trades.none { it.reason == "CHART_EXIT" }, "CHART_EXIT must not occur when disabled")
    }
}
