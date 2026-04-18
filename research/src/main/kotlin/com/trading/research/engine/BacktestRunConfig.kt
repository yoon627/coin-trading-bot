package com.trading.research.engine

import com.trading.research.domain.Asset
import com.trading.research.domain.Bar
import com.trading.research.execution.CostModel
import com.trading.research.risk.KillSwitch
import com.trading.research.risk.RiskPolicy
import com.trading.research.strategy.ResearchStrategy
import java.time.Instant

/**
 * Immutable configuration bundle for a single backtest invocation.
 *
 * Grouping inputs into one data class keeps [Engine.run] signature stable as the
 * framework grows (new knobs land here, not as positional parameters), and makes
 * it trivial to reuse a config across walk-forward / parameter-grid runs.
 *
 * [warmupUntil]: inclusive upper bound of the warmup phase. Bars whose `closeTime`
 * is at-or-before this instant advance the clock and [com.trading.research.strategy.RollingUniverseView]
 * (so strategies see prior history on the first real bar) but do NOT produce fills,
 * update the kill-switch peak, accrue metrics, or invoke the strategy. `null` means
 * no warmup phase (the default for plain backtests). WalkForwardRunner populates
 * this so pre-roll bars cannot contaminate train/test PnL.
 */
data class BacktestRunConfig(
    val strategy: ResearchStrategy,
    val history: Map<Asset, List<Bar>>,
    val initialCash: Double,
    val costModel: CostModel,
    val risk: RiskPolicy,
    val killSwitch: KillSwitch,
    val params: Map<String, Any> = emptyMap(),
    val warmupUntil: Instant? = null,
)
