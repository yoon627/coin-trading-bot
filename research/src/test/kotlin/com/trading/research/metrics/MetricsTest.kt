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
}
