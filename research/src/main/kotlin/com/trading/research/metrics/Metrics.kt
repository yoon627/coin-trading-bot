package com.trading.research.metrics

import kotlin.math.sqrt

object Metrics {

    // Epsilon guard for near-zero floating-point stdev comparisons (catches FP drift on constant inputs).
    private const val STDEV_EPS = 1e-12

    fun periodReturns(equity: List<Double>): List<Double> {
        if (equity.size < 2) return emptyList()
        return equity.zipWithNext { prev, curr -> (curr - prev) / prev }
    }

    fun annualizedSharpe(returns: List<Double>, periodsPerYear: Int): Double {
        if (returns.size < 2) return 0.0
        val mean = returns.average()
        val variance = returns.sumOf { (it - mean) * (it - mean) } / returns.size
        val stdev = sqrt(variance)
        if (stdev < STDEV_EPS) return 0.0
        return (mean / stdev) * sqrt(periodsPerYear.toDouble())
    }

    fun annualizedSortino(returns: List<Double>, periodsPerYear: Int): Double {
        if (returns.size < 2) return 0.0
        val mean = returns.average()
        val downside = returns.filter { it < 0.0 }
        if (downside.isEmpty()) return 0.0
        val downVariance = downside.sumOf { it * it } / returns.size
        val downStdev = sqrt(downVariance)
        if (downStdev < STDEV_EPS) return 0.0
        return (mean / downStdev) * sqrt(periodsPerYear.toDouble())
    }

    fun maxDrawdown(equity: List<Double>): Double {
        if (equity.size < 2) return 0.0
        var peak = equity[0]
        var maxDd = 0.0
        for (e in equity) {
            if (e > peak) peak = e
            val dd = (peak - e) / peak
            if (dd > maxDd) maxDd = dd
        }
        return maxDd
    }

    fun calmar(annualizedReturn: Double, maxDd: Double): Double =
        if (maxDd == 0.0) 0.0 else annualizedReturn / maxDd

    /** percentile: 0.95 → 5% tail (loss). Returns negative for loss. */
    fun historicalVar(returns: List<Double>, percentile: Double): Double {
        require(percentile in 0.5..0.9999) { "percentile in (0.5, 1)" }
        if (returns.isEmpty()) return 0.0
        val sorted = returns.sorted()
        val idx = ((1.0 - percentile) * sorted.size).toInt().coerceAtLeast(0)
        return sorted[idx]
    }

    fun historicalCvar(returns: List<Double>, percentile: Double): Double {
        if (returns.isEmpty()) return 0.0
        val sorted = returns.sorted()
        val cutoff = ((1.0 - percentile) * sorted.size).toInt().coerceAtLeast(1)
        val tail = sorted.take(cutoff)
        return tail.average()
    }

    fun winRate(tradePnls: List<Double>): Double =
        if (tradePnls.isEmpty()) 0.0 else tradePnls.count { it > 0 }.toDouble() / tradePnls.size

    fun profitFactor(tradePnls: List<Double>): Double {
        val grossProfit = tradePnls.filter { it > 0 }.sum()
        val grossLoss = -tradePnls.filter { it < 0 }.sum()
        return if (grossLoss == 0.0) if (grossProfit > 0) Double.POSITIVE_INFINITY else 0.0
        else grossProfit / grossLoss
    }
}
