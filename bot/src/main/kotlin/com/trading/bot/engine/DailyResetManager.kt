package com.trading.bot.engine

import com.trading.bot.domain.TradingState
import com.trading.common.config.TradingProperties
import com.trading.common.strategy.ExitGates
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

class DailyResetManager(
    private val tradingProperties: TradingProperties,
    private val clock: Clock = Clock.system(KST),
) {
    companion object {
        private val KST: ZoneId = ZoneId.of("Asia/Seoul")
    }

    private val log = LoggerFactory.getLogger(javaClass)
    private val resetTime = LocalTime.of(9, 0)

    private var lastResetDate: LocalDate? = null

    fun getTradingDate(): LocalDate {
        val now = ZonedDateTime.now(clock)
        return if (now.toLocalTime().isBefore(resetTime)) {
            now.toLocalDate().minusDays(1)
        } else {
            now.toLocalDate()
        }
    }

    fun checkAndReset(states: Map<String, TradingState>): Boolean {
        val tradingDate = getTradingDate()
        if (lastResetDate == tradingDate) return false

        log.info("Daily reset triggered for trading date: {}", tradingDate)
        states.values.forEach { it.resetDaily() }
        lastResetDate = tradingDate
        return true
    }

    fun shouldSellForDailyReset(state: TradingState): Boolean {
        if (!state.position) return false
        val buyDate = state.buyDate ?: return false
        val holdLimit = ExitGates.effectiveMaxHoldDays(tradingProperties.maxHoldDays)
        return ChronoUnit.DAYS.between(buyDate, getTradingDate()) >= holdLimit
    }
}
