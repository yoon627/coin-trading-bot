package com.trading.research.engine

import com.trading.research.domain.Asset
import com.trading.research.domain.Bar
import com.trading.research.execution.CostModel
import com.trading.research.risk.KillSwitch
import com.trading.research.risk.RiskPolicy
import com.trading.research.strategy.ResearchStrategy

/**
 * Immutable configuration bundle for a single backtest invocation.
 *
 * Grouping inputs into one data class keeps [Engine.run] signature stable as the
 * framework grows (new knobs land here, not as positional parameters), and makes
 * it trivial to reuse a config across walk-forward / parameter-grid runs.
 */
data class BacktestRunConfig(
    val strategy: ResearchStrategy,
    val history: Map<Asset, List<Bar>>,
    val initialCash: Double,
    val costModel: CostModel,
    val risk: RiskPolicy,
    val killSwitch: KillSwitch,
    val params: Map<String, Any> = emptyMap(),
)
