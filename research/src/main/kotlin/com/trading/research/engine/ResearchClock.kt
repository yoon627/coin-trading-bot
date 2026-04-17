package com.trading.research.engine

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class ResearchClock(private val zone: ZoneId = ZoneId.of("UTC")) {
    private var _now: Instant = Instant.EPOCH

    val now: Instant get() = _now

    fun advanceTo(t: Instant) {
        require(!t.isBefore(_now)) { "clock cannot go backwards: $_now → $t" }
        _now = t
    }

    fun currentDate(): LocalDate = _now.atZone(zone).toLocalDate()
}
