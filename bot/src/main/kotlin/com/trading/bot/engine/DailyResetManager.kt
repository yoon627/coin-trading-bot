package com.trading.bot.engine

import com.trading.bot.domain.TradingDay
import com.trading.bot.domain.TradingState
import com.trading.common.config.TradingProperties
import com.trading.common.strategy.ExitGates
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.LocalDate
import java.time.temporal.ChronoUnit

class DailyResetManager(
    private val tradingProperties: TradingProperties,
    private val clock: Clock = Clock.system(TradingDay.KST),
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private var lastResetDate: LocalDate? = null

    fun getTradingDate(): LocalDate = TradingDay.of(clock)

    fun checkAndReset(states: Map<String, TradingState>): Boolean {
        val tradingDate = getTradingDate()
        if (lastResetDate == tradingDate) return false

        log.info("Daily reset triggered for trading date: {}", tradingDate)
        // 해제 판정은 state 별 boughtDate 로 한다 — lastResetDate 는 프로세스 수명 캐시일 뿐이라
        // 재시작 직후엔 항상 null 이고, 그때 무조건 해제하면 durable boughtToday 가 지워져 당일 재매수가 뚫린다.
        states.values.forEach { it.resetDaily(tradingDate) }
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
