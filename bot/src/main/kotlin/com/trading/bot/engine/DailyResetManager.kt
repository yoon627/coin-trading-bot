package com.trading.bot.engine

import com.trading.bot.domain.TradingState
import com.trading.common.config.TradingProperties
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

class DailyResetManager(
    private val tradingProperties: TradingProperties = TradingProperties(),
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
        // coerce: env 오설정(0/음수)이 매수 당일 즉시 청산 루프(일 1회 왕복 수수료 손실)가 되지 않도록.
        val holdLimit = tradingProperties.maxHoldDays.coerceAtLeast(1)
        return ChronoUnit.DAYS.between(buyDate, getTradingDate()) >= holdLimit
    }
}
