package com.trading.research.walkforward

import com.trading.research.engine.BacktestRunConfig
import com.trading.research.engine.Engine
import com.trading.research.engine.RunResult
import com.trading.research.metrics.Metrics

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
        val evaluations = grid.combinations().toList().map { params ->
            val trainCfg = baseConfig.copy(params = params)
            params to Engine.run(trainCfg)
        }
        require(evaluations.isNotEmpty()) { "grid produced no parameter combinations" }

        val best = evaluations.maxBy { scoreOf(it.second, optimizeFor) }
        val testCfg = baseConfig.copy(params = best.first)
        val testResult = Engine.run(testCfg)
        return WindowOutcome(
            window = window,
            bestParams = best.first,
            trainResult = best.second,
            testResult = testResult,
        )
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
    }
}
