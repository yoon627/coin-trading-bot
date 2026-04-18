package com.trading.research.sizing

import com.trading.research.domain.SizingRule
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.sqrt

class SizingCalculatorTest {
    private fun near(a: Double, b: Double, tol: Double = 1e-6) =
        assert(abs(a - b) < tol) { "a=$a b=$b" }

    @Test
    fun `FixedFraction returns equity times fraction`() {
        val out = SizingCalculator.notional(SizingRule.FixedFraction(0.1), equity = 10_000.0, assetDailyVol = 0.02)
        near(out, 1_000.0)
    }

    @Test
    fun `VolTarget annualVol 0_15 asset dailyVol 0_02 equity 10000`() {
        val rule = SizingRule.VolTarget(annualVol = 0.15, lookbackDays = 20)
        val out = SizingCalculator.notional(rule, equity = 10_000.0, assetDailyVol = 0.02)
        val expected = 10_000.0 * (0.15 / (0.02 * sqrt(252.0)))
        near(out, expected, tol = 1e-3)
    }

    @Test
    fun `Notional returns fixed amount`() {
        val out = SizingCalculator.notional(SizingRule.Notional(5_000.0), equity = 100_000.0, assetDailyVol = 0.02)
        near(out, 5_000.0)
    }

    @Test
    fun `CloseAll returns 0 notional, handled by caller as exit`() {
        val out = SizingCalculator.notional(SizingRule.CloseAll, equity = 100.0, assetDailyVol = 0.02)
        near(out, 0.0)
    }

    @Test
    fun `VolTarget with zero or negative assetVol fails loudly instead of silent fallback`() {
        // A silent fallback to 10% FixedFraction produced wrong exposure/PnL whenever callers
        // opted into VolTarget without supplying realized vol (caught in Apr 2026 codex review).
        val rule = SizingRule.VolTarget(annualVol = 0.15)
        assertThrows(IllegalStateException::class.java) {
            SizingCalculator.notional(rule, equity = 10_000.0, assetDailyVol = 0.0)
        }
        assertThrows(IllegalStateException::class.java) {
            SizingCalculator.notional(rule, equity = 10_000.0, assetDailyVol = -0.01)
        }
    }
}
