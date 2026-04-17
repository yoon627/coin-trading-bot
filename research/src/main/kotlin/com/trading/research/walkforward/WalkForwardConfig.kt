package com.trading.research.walkforward

import java.time.LocalDate

/**
 * Configuration for rolling walk-forward windows.
 *
 * Each window partitions [from, to] into a training slice of [trainDays] immediately
 * followed by an out-of-sample test slice of [testDays]. The next window's trainStart
 * advances by [stepDays], producing overlapping walk-forward folds. Windows that would
 * run past [to] are dropped — partial/ragged tails corrupt parameter-stability stats.
 */
data class WalkForwardConfig(
    val trainDays: Int = DEFAULT_TRAIN_DAYS,
    val testDays: Int = DEFAULT_TEST_DAYS,
    val stepDays: Int = DEFAULT_STEP_DAYS,
) {
    data class Window(
        val trainStart: LocalDate,
        val trainEnd: LocalDate,
        val testStart: LocalDate,
        val testEnd: LocalDate,
    )

    fun splitWindows(from: LocalDate, to: LocalDate): List<Window> {
        val windows = mutableListOf<Window>()
        var trainStart = from
        while (true) {
            val trainEnd = trainStart.plusDays(trainDays.toLong())
            val testEnd = trainEnd.plusDays(testDays.toLong())
            if (testEnd.isAfter(to)) break
            windows.add(Window(trainStart, trainEnd, trainEnd, testEnd))
            trainStart = trainStart.plusDays(stepDays.toLong())
        }
        return windows
    }

    companion object {
        const val DEFAULT_TRAIN_DAYS = 730
        const val DEFAULT_TEST_DAYS = 180
        const val DEFAULT_STEP_DAYS = 90
    }
}
