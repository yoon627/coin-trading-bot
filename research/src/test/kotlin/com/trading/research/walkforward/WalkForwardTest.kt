package com.trading.research.walkforward

import com.trading.common.domain.Exchange
import com.trading.research.domain.Asset
import com.trading.research.domain.Bar
import com.trading.research.domain.OrderRequest
import com.trading.research.engine.BacktestRunConfig
import com.trading.research.execution.FlatFeeSlippageModel
import com.trading.research.risk.KillSwitch
import com.trading.research.risk.RiskPolicy
import com.trading.research.strategy.BarEvent
import com.trading.research.strategy.ResearchContext
import com.trading.research.strategy.ResearchStrategy
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

class WalkForwardTest {
    @Test
    fun `splits 3-year period into rolling windows train730 test180 step90`() {
        val from = LocalDate.of(2021, 1, 1)
        val to = LocalDate.of(2024, 1, 1) // 3 years
        val cfg = WalkForwardConfig(trainDays = 730, testDays = 180, stepDays = 90)
        val windows = cfg.splitWindows(from, to)

        assertTrue(windows.size >= 3, "expected several windows, got ${windows.size}")
        windows.forEach { w ->
            assertEquals(730L, ChronoUnit.DAYS.between(w.trainStart, w.trainEnd))
            assertEquals(180L, ChronoUnit.DAYS.between(w.testStart, w.testEnd))
            assertEquals(w.trainEnd, w.testStart)
        }
        for (i in 1 until windows.size) {
            assertEquals(90L, ChronoUnit.DAYS.between(windows[i - 1].trainStart, windows[i].trainStart))
        }
    }

    @Test
    fun `parameter grid enumerates all combinations`() {
        val grid = ParameterGrid(mapOf(
            "rsi" to listOf(20, 30, 40),
            "ma"  to listOf(10, 20),
        ))
        val combos = grid.combinations().toList()
        assertEquals(6, combos.size)
        assertTrue(combos.contains(mapOf("rsi" to 20, "ma" to 10)))
    }

    @Test
    fun `run slices baseConfig history to train and test windows`() = runTest {
        val asset = Asset(Exchange.UPBIT, "BTC/KRW")
        val utc = ZoneId.of("UTC")
        val dayStart = LocalDate.of(2024, 1, 1)
        // 30 daily UTC bars. Bar i has openTime 2024-01-01+i, closeTime 2024-01-02+i.
        val bars = (0L..29L).map { i ->
            val open = dayStart.plusDays(i).atStartOfDay(utc).toInstant()
            Bar(open, open.plusSeconds(86400), 100.0, 100.0, 100.0, 100.0, 1.0, 100.0)
        }
        // Windows are indexed by closeTime UTC date (matches Engine clock semantics):
        // train [2024-01-02, 2024-01-12) captures bars 0..9 (closeDates 01-02..01-11).
        // test  [2024-01-12, 2024-01-22) captures bars 10..19 (closeDates 01-12..01-21).
        val window = WalkForwardConfig.Window(
            trainStart = LocalDate.of(2024, 1, 2),
            trainEnd = LocalDate.of(2024, 1, 12),
            testStart = LocalDate.of(2024, 1, 12),
            testEnd = LocalDate.of(2024, 1, 22),
        )
        val baseConfig = BacktestRunConfig(
            strategy = NoopStrategy,
            history = mapOf(asset to bars),
            initialCash = 10_000.0,
            costModel = FlatFeeSlippageModel(feeRate = 0.0, slippageBps = 0.0),
            risk = RiskPolicy(
                stopLossPct = null,
                trailingStopPct = null,
                takeProfitPct = null,
                timeExitBars = null,
            ),
            killSwitch = KillSwitch(),
        )
        val runner = WalkForwardRunner(
            grid = ParameterGrid(emptyMap()),
            config = WalkForwardConfig(),
        )

        val outcome = runner.run(baseConfig, window)

        // Without slicing, both train and test would see all 30 bars.
        // With proper slicing, each sees exactly its window's 10 bars.
        assertEquals(10, outcome.trainResult.equityCurve.size, "train must slice to window")
        assertEquals(10, outcome.testResult.equityCurve.size, "test must slice to window")
    }

    @Test
    fun `run prepends warmup buffer bars before the window start`() = runTest {
        val asset = Asset(Exchange.UPBIT, "BTC/KRW")
        val utc = ZoneId.of("UTC")
        val dayStart = LocalDate.of(2024, 1, 1)
        // 40 daily UTC bars. Window starts at bar index 20 (closeDate = 01-22).
        val bars = (0L..39L).map { i ->
            val open = dayStart.plusDays(i).atStartOfDay(utc).toInstant()
            Bar(open, open.plusSeconds(86400), 100.0, 100.0, 100.0, 100.0, 1.0, 100.0)
        }
        // Strategy reports how many recentBars it sees on the first bar Engine hands it.
        val observer = FirstBarObserverStrategy(warmupBars = 5)

        val window = WalkForwardConfig.Window(
            trainStart = LocalDate.of(2024, 1, 22),   // bar index 20's closeDate
            trainEnd = LocalDate.of(2024, 2, 1),      // 10-day window
            testStart = LocalDate.of(2024, 2, 1),
            testEnd = LocalDate.of(2024, 2, 11),
        )
        val baseConfig = BacktestRunConfig(
            strategy = observer,
            history = mapOf(asset to bars),
            initialCash = 10_000.0,
            costModel = FlatFeeSlippageModel(feeRate = 0.0, slippageBps = 0.0),
            risk = RiskPolicy(null, null, null, null),
            killSwitch = KillSwitch(),
        )
        val runner = WalkForwardRunner(
            grid = ParameterGrid(emptyMap()),
            config = WalkForwardConfig(),
        )

        val outcome = runner.run(baseConfig, window)

        // Warmup bars advance clock + universe so history is available, but they must NOT
        // produce fills, touch the kill-switch peak, or land in the equity curve — otherwise
        // pre-window PnL silently contaminates walk-forward scoring.
        assertEquals(10, outcome.trainResult.equityCurve.size, "train equity curve excludes warmup")
        assertEquals(10, outcome.testResult.equityCurve.size, "test equity curve excludes warmup")
        // Strategy invocations only happen on in-window bars: 10 train + 10 test.
        assertEquals(20, observer.totalInvocations, "strategy must be skipped during warmup")
        // Yet the first in-window call still sees warmup history via ctx.universe.recentBars().
        assertTrue(
            observer.firstInvocationRecentBarCount >= 5,
            "first in-window invocation must see ≥ warmupBars historical bars; " +
                "got ${observer.firstInvocationRecentBarCount}",
        )
    }

    private object NoopStrategy : ResearchStrategy {
        override val name = "Noop"
        override val warmupBars = 0
        override suspend fun onBar(ctx: ResearchContext, event: BarEvent): List<OrderRequest> = emptyList()
    }

    private class FirstBarObserverStrategy(override val warmupBars: Int) : ResearchStrategy {
        override val name = "FirstBarObserver"
        var totalInvocations: Int = 0; private set
        var firstInvocationRecentBarCount: Int = -1; private set
        override suspend fun onBar(ctx: ResearchContext, event: BarEvent): List<OrderRequest> {
            if (firstInvocationRecentBarCount < 0) {
                firstInvocationRecentBarCount = ctx.universe.recentBars(event.asset, Int.MAX_VALUE).size
            }
            totalInvocations++
            return emptyList()
        }
    }
}
