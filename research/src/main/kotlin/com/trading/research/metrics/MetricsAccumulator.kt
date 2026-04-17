package com.trading.research.metrics

import com.trading.research.engine.EquityPoint
import java.time.LocalDate

/**
 * Collects one equity reading per calendar date during a backtest run.
 *
 * Multiple bars on the same date overwrite the prior reading, so [curve] reflects
 * end-of-day equity — the canonical input to Sharpe/Sortino/MaxDD metrics.
 */
class MetricsAccumulator {
    private val byDate = linkedMapOf<LocalDate, Double>()

    fun recordDailyEquity(date: LocalDate, equity: Double) {
        byDate[date] = equity
    }

    fun curve(): List<EquityPoint> = byDate.map { (date, equity) -> EquityPoint(date, equity) }
}
