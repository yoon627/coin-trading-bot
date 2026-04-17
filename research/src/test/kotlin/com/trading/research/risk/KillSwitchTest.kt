package com.trading.research.risk

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate

class KillSwitchTest {

    @Test
    fun `daily drawdown halts new entries after threshold`() {
        val ks = KillSwitch(dailyDdHaltPct = 0.05, totalDdHaltPct = null)
        val today = LocalDate.of(2024, 1, 15)
        ks.onDayStart(today, startEquity = 10_000.0)

        assertFalse(ks.shouldBlockEntries(today, currentEquity = 9_600.0))  // 4% down
        assertTrue(ks.shouldBlockEntries(today, currentEquity = 9_400.0))   // 6% down → halt
    }

    @Test
    fun `total drawdown halts simulation entirely`() {
        val ks = KillSwitch(dailyDdHaltPct = null, totalDdHaltPct = 0.20)
        ks.onPeakUpdate(peakEquity = 10_000.0)
        assertFalse(ks.shouldHaltSimulation(currentEquity = 8_500.0))  // 15%
        assertTrue(ks.shouldHaltSimulation(currentEquity = 7_900.0))   // 21% → halt
    }
}
