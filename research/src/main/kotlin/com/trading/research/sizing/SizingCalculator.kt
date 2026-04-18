package com.trading.research.sizing

import com.trading.research.domain.SizingRule
import kotlin.math.sqrt

object SizingCalculator {
    private const val TRADING_DAYS_PER_YEAR = 252

    /**
     * Target notional (quote currency) for a new position.
     * assetDailyVol = realized daily stdev of returns over the rule's lookback.
     *
     * Throws [IllegalStateException] when [rule] is [SizingRule.VolTarget] but assetDailyVol
     * is non-positive: the original silent fallback to 10% equity produced exposure and PnL
     * that did NOT reflect the caller's vol target, masking sizing bugs in backtests.
     */
    fun notional(rule: SizingRule, equity: Double, assetDailyVol: Double): Double = when (rule) {
        is SizingRule.FixedFraction -> equity * rule.fractionOfEquity
        is SizingRule.Notional -> rule.amount
        is SizingRule.CloseAll -> 0.0
        is SizingRule.VolTarget -> {
            check(assetDailyVol > 0.0) {
                "VolTarget sizing requires a positive assetDailyVol; caller supplied $assetDailyVol. " +
                    "Compute realized volatility for the target asset before submitting vol-targeted orders."
            }
            val sigmaAnnual = assetDailyVol * sqrt(TRADING_DAYS_PER_YEAR.toDouble())
            equity * (rule.annualVol / sigmaAnnual)
        }
    }
}
