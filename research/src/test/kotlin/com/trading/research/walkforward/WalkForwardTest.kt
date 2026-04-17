package com.trading.research.walkforward

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.temporal.ChronoUnit

class WalkForwardTest {
    @Test
    fun `splits 3-year period into rolling windows train730 test180 step90`() {
        val from = LocalDate.of(2021, 1, 1)
        val to = LocalDate.of(2024, 1, 1) // 3 years
        val cfg = WalkForwardConfig(trainDays = 730, testDays = 180, stepDays = 90)
        val windows = cfg.splitWindows(from, to)

        assertTrue(windows.size >= 3, "expected several windows, got ${windows.size}")
        windows.forEach { w ->
            assertEquals(730L, ChronoUnit.DAYS.between(w.trainStart, w.trainEnd))
            assertEquals(180L, ChronoUnit.DAYS.between(w.testStart, w.testEnd))
            assertEquals(w.trainEnd, w.testStart)
        }
        for (i in 1 until windows.size) {
            assertEquals(90L, ChronoUnit.DAYS.between(windows[i - 1].trainStart, windows[i].trainStart))
        }
    }

    @Test
    fun `parameter grid enumerates all combinations`() {
        val grid = ParameterGrid(mapOf(
            "rsi" to listOf(20, 30, 40),
            "ma"  to listOf(10, 20),
        ))
        val combos = grid.combinations().toList()
        assertEquals(6, combos.size)
        assertTrue(combos.contains(mapOf("rsi" to 20, "ma" to 10)))
    }
}
