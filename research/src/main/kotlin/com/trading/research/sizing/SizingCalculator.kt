package com.trading.research.sizing

import com.trading.research.domain.SizingRule
import kotlin.math.sqrt

object SizingCalculator {
    private const val FALLBACK_FRACTION = 0.1
    private const val TRADING_DAYS_PER_YEAR = 252

    /**
     * Target notional (quote currency) for a new position.
     * assetDailyVol = realized daily stdev of returns over the rule's lookback.
     */
    fun notional(rule: SizingRule, equity: Double, assetDailyVol: Double): Double = when (rule) {
        is SizingRule.FixedFraction -> equity * rule.fractionOfEquity
        is SizingRule.Notional -> rule.amount
        is SizingRule.CloseAll -> 0.0
        is SizingRule.VolTarget -> {
            if (assetDailyVol <= 0.0) equity * FALLBACK_FRACTION
            else {
                val sigmaAnnual = assetDailyVol * sqrt(TRADING_DAYS_PER_YEAR.toDouble())
                equity * (rule.annualVol / sigmaAnnual)
            }
        }
    }
}
