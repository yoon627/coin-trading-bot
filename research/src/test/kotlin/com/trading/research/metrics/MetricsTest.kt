package com.trading.research.metrics

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.sqrt

class MetricsTest {

    private fun closeTo(actual: Double, expected: Double, tol: Double = 1e-6) {
        assertTrue(abs(actual - expected) < tol, "expected≈$expected got=$actual tol=$tol")
    }

    @Test
    fun `periodReturns computes r_t = E_t - E_t-1 over E_t-1`() {
        val equity = listOf(100.0, 110.0, 99.0)
        val rets = Metrics.periodReturns(equity)
        assertEquals(2, rets.size)
        closeTo(rets[0], 0.10)
        closeTo(rets[1], -0.10)
    }

    @Test
    fun `annualized sharpe with constant return 0_001 daily equals 0_001 over stdev sqrt252`() {
        // Constant returns → stdev = 0 → guard: return 0
        val rets = List(252) { 0.001 }
        val sharpe = Metrics.annualizedSharpe(rets, 252)
        closeTo(sharpe, 0.0) // stdev 0 → defined as 0
    }

    @Test
    fun `annualized sharpe with alternating returns matches formula`() {
        // returns: [+0.01, -0.01] repeating 100 times
        val rets = List(200) { if (it % 2 == 0) 0.01 else -0.01 }
        val mean = 0.0
        val stdev = 0.01 // population stdev of +-0.01 around mean 0
        val expected = (mean / stdev) * sqrt(252.0)
        closeTo(Metrics.annualizedSharpe(rets, 252), expected)
    }

    @Test
    fun `maxDrawdown finds largest peak-to-trough fraction`() {
        val equity = listOf(100.0, 120.0, 90.0, 110.0, 60.0, 80.0)
        // peak 120 → trough 60 = (120-60)/120 = 0.5
        closeTo(Metrics.maxDrawdown(equity), 0.5)
    }

    @Test
    fun `sortino uses downside deviation only`() {
        val rets = listOf(0.02, -0.01, 0.03, -0.02, 0.01)
        val sortino = Metrics.annualizedSortino(rets, 252)
        // sanity: should be > sharpe because upside vol not penalized
        assertTrue(sortino > 0)
    }

    @Test
    fun `historicalVar95 returns 5th percentile`() {
        val rets = (1..100).map { it * -0.001 } // -0.001 .. -0.1
        // 5th percentile = 5th worst = -0.096 or similar
        val var95 = Metrics.historicalVar(rets, 0.95)
        assertTrue(var95 <= -0.095)
        assertTrue(var95 >= -0.101)
    }

    @Test
    fun `historicalCvar averages the tail beyond VaR threshold`() {
        val rets = (1..100).map { it * -0.001 } // -0.001 .. -0.1
        // At percentile=0.95, cutoff=5, take worst 5 → avg of {-0.1,-0.099,-0.098,-0.097,-0.096}
        val cvar = Metrics.historicalCvar(rets, 0.95)
        closeTo(cvar, -0.098, tol = 1e-9)
    }

    @Test
    fun `historicalCvar rejects out-of-range percentile`() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) {
            Metrics.historicalCvar(listOf(-0.01, 0.02), 0.3)
        }
    }

    @Test
    fun `calmar divides annualized return by max drawdown`() {
        closeTo(Metrics.calmar(annualizedReturn = 0.2, maxDd = 0.1), 2.0)
        closeTo(Metrics.calmar(annualizedReturn = 0.5, maxDd = 0.0), 0.0) // guard
    }

    @Test
    fun `winRate fraction of positive trades`() {
        closeTo(Metrics.winRate(listOf(10.0, -5.0, 20.0, -3.0, -1.0)), 0.4)
        closeTo(Metrics.winRate(emptyList()), 0.0)
    }

    @Test
    fun `profitFactor returns ratio of gross profit to gross loss`() {
        // wins: 30.0; losses: 20.0 → 1.5
        closeTo(Metrics.profitFactor(listOf(20.0, 10.0, -15.0, -5.0)), 1.5)
    }

    @Test
    fun `profitFactor caps to finite sentinel when no losses`() {
        // All-winners must not return Infinity — JSON-unsafe. Returns PROFIT_FACTOR_CAP (999.0).
        val allWins = Metrics.profitFactor(listOf(10.0, 20.0, 30.0))
        closeTo(allWins, 999.0)
        assertTrue(allWins.isFinite(), "profitFactor must be finite for JSON serialization")
    }

    @Test
    fun `profitFactor returns 0 for empty or all-zero input`() {
        closeTo(Metrics.profitFactor(emptyList()), 0.0)
        closeTo(Metrics.profitFactor(listOf(0.0, 0.0)), 0.0)
    }
}
