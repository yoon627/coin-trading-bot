package com.trading.research.walkforward

import com.trading.research.domain.Asset
import com.trading.research.domain.Bar
import com.trading.research.engine.BacktestRunConfig
import com.trading.research.engine.Engine
import com.trading.research.engine.RunResult
import com.trading.research.metrics.Metrics
import java.time.LocalDate
import java.time.ZoneId

/**
 * Drives a single walk-forward window: grid-search over the training slice,
 * pick the best parameter set by [OptimizeFor], then evaluate it on the
 * out-of-sample test slice.
 *
 * Callers own the loop over windows so they can parallelize, persist, or
 * early-stop without this class dictating a schedule.
 */
class WalkForwardRunner(
    private val grid: ParameterGrid,
    private val config: WalkForwardConfig,
) {

    enum class OptimizeFor { SHARPE, CALMAR, TOTAL_RETURN }

    data class WindowOutcome(
        val window: WalkForwardConfig.Window,
        val bestParams: Map<String, Any>,
        val trainResult: RunResult,
        val testResult: RunResult,
    )

    suspend fun run(
        baseConfig: BacktestRunConfig,
        window: WalkForwardConfig.Window,
        optimizeFor: OptimizeFor = OptimizeFor.SHARPE,
    ): WindowOutcome {
        val warmupBars = baseConfig.strategy.warmupBars
        val trainHistory = sliceHistory(baseConfig.history, window.trainStart, window.trainEnd, warmupBars)
        val testHistory = sliceHistory(baseConfig.history, window.testStart, window.testEnd, warmupBars)
        requireWindowHasBars(trainHistory, window.trainStart, window.trainEnd, label = "train")
        requireWindowHasBars(testHistory, window.testStart, window.testEnd, label = "test")

        val evaluations = grid.combinations().toList().map { params ->
            val trainCfg = baseConfig.copy(history = trainHistory, params = params)
            params to Engine.run(trainCfg)
        }
        require(evaluations.isNotEmpty()) { "grid produced no parameter combinations" }

        val best = evaluations.maxBy { scoreOf(it.second, optimizeFor) }
        val testCfg = baseConfig.copy(history = testHistory, params = best.first)
        val testResult = Engine.run(testCfg)
        return WindowOutcome(
            window = window,
            bestParams = best.first,
            trainResult = best.second,
            testResult = testResult,
        )
    }

    /**
     * Half-open window semantics: a bar belongs to [from, to) when its closeTime's UTC
     * date is on-or-after [from] and strictly before [to]. An additional [warmupBars]
     * bars immediately preceding [from] are prepended so strategies whose [warmupBars] > 0
     * (e.g. indicator-based legacy adapters with lookback ≥ 50) can warm up before the
     * window officially opens — otherwise the first [warmupBars] bars of every fold would
     * emit no signals and bias walk-forward scores.
     *
     * closeTime (not openTime) is authoritative because Engine advances the clock on
     * closeTime and exposes bar information to strategies at close. Slicing by openTime
     * would misroute any bar that straddles a UTC date boundary (e.g. a daily KST candle
     * whose open is 15:00Z day N-1 but whose close is 15:00Z day N) — the engine processes
     * its close in the later window while the open lies in the earlier one, leaking
     * close-side information across the train/test split.
     *
     * NOTE: metrics accumulate over the full sliced range including the pre-roll buffer,
     * so Sharpe/MaxDD will include warmup bars at (typically flat) pre-signal equity.
     * Callers needing warmup-excluded metrics should post-process the returned equity curve.
     */
    private fun sliceHistory(
        history: Map<Asset, List<Bar>>,
        from: LocalDate,
        to: LocalDate,
        warmupBars: Int,
    ): Map<Asset, List<Bar>> = history.mapValues { (_, bars) ->
        val windowStart = bars.indexOfFirst {
            !it.closeTime.atZone(WINDOW_ZONE).toLocalDate().isBefore(from)
        }
        if (windowStart < 0) return@mapValues emptyList()
        val windowEnd = bars.indexOfFirst {
            !it.closeTime.atZone(WINDOW_ZONE).toLocalDate().isBefore(to)
        }.let { if (it < 0) bars.size else it }
        val preRollStart = (windowStart - warmupBars).coerceAtLeast(0)
        bars.subList(preRollStart, windowEnd)
    }

    private fun requireWindowHasBars(
        sliced: Map<Asset, List<Bar>>,
        from: LocalDate,
        to: LocalDate,
        label: String,
    ) {
        val windowHasBars = sliced.values.any { bars ->
            bars.any {
                val d = it.closeTime.atZone(WINDOW_ZONE).toLocalDate()
                !d.isBefore(from) && d.isBefore(to)
            }
        }
        require(windowHasBars) { "no bars in $label window $from..$to" }
    }

    private fun scoreOf(result: RunResult, optimizeFor: OptimizeFor): Double {
        val equity = result.equityCurve.map { it.equity }
        if (equity.size < 2) return 0.0
        val returns = Metrics.periodReturns(equity)
        return when (optimizeFor) {
            OptimizeFor.SHARPE -> Metrics.annualizedSharpe(returns, TRADING_DAYS_PER_YEAR)
            OptimizeFor.CALMAR -> calmarOf(result, equity)
            OptimizeFor.TOTAL_RETURN -> totalReturnOf(result, equity)
        }
    }

    private fun calmarOf(result: RunResult, equity: List<Double>): Double {
        val maxDrawdown = Metrics.maxDrawdown(equity)
        if (maxDrawdown == 0.0) return 0.0
        return totalReturnOf(result, equity) / maxDrawdown
    }

    private fun totalReturnOf(result: RunResult, equity: List<Double>): Double {
        val initialEquity = equity.first()
        if (initialEquity == 0.0) return 0.0
        return result.finalEquity / initialEquity - 1.0
    }

    companion object {
        private const val TRADING_DAYS_PER_YEAR = 252
        private val WINDOW_ZONE: ZoneId = ZoneId.of("UTC")
    }
}
