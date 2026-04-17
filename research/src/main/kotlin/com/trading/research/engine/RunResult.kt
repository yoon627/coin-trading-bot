package com.trading.research.engine

import com.trading.research.domain.Asset
import com.trading.research.portfolio.Fill
import java.time.LocalDate

data class EquityPoint(val date: LocalDate, val equity: Double)

/**
 * Result of a single backtest run.
 *
 * Retains both raw [fills] and aggregated [tradesClosed] so downstream reporters
 * (CSV, JSON, Markdown) can render execution-level and trade-level views without
 * re-deriving one from the other.
 */
data class RunResult(
    val strategyName: String,
    val fills: List<Fill>,
    val equityCurve: List<EquityPoint>,
    val finalEquity: Double,
    val tradesClosed: List<ClosedTrade>,
)

data class ClosedTrade(
    val asset: Asset,
    val entryBarIndex: Long,
    val exitBarIndex: Long,
    val entryPrice: Double,
    val exitPrice: Double,
    val quantity: Double,
    val pnl: Double,
    val reason: String,
)
