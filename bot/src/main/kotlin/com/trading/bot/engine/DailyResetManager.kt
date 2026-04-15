package com.trading.bot.engine

import com.trading.bot.domain.TradingState
import org.slf4j.LoggerFactory
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class DailyResetManager {
    private val log = LoggerFactory.getLogger(javaClass)
    private val kst = ZoneId.of("Asia/Seoul")
    private val resetTime = LocalTime.of(9, 0)

    private var lastResetDate: LocalDate? = null

    fun getTradingDate(): LocalDate {
        val now = ZonedDateTime.now(kst)
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
        val tradingDate = getTradingDate()
        val buyDate = state.buyDate ?: return false
        return buyDate < tradingDate
    }
}
