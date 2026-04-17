package com.trading.research.risk

import java.time.LocalDate

class KillSwitch(
    private val dailyDdHaltPct: Double? = null,
    private val totalDdHaltPct: Double? = null,
) {
    private var currentDay: LocalDate? = null
    private var dayStartEquity: Double = 0.0
    private var peakEquity: Double = 0.0

    fun onDayStart(day: LocalDate, startEquity: Double) {
        currentDay = day
        dayStartEquity = startEquity
    }

    fun onPeakUpdate(peakEquity: Double) {
        if (peakEquity > this.peakEquity) this.peakEquity = peakEquity
    }

    fun shouldBlockEntries(day: LocalDate, currentEquity: Double): Boolean {
        if (dailyDdHaltPct == null) return false
        if (currentDay != day) return false
        val dd = (dayStartEquity - currentEquity) / dayStartEquity
        return dd >= dailyDdHaltPct
    }

    fun shouldHaltSimulation(currentEquity: Double): Boolean {
        if (totalDdHaltPct == null || peakEquity <= 0.0) return false
        val dd = (peakEquity - currentEquity) / peakEquity
        return dd >= totalDdHaltPct
    }
}
