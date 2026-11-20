package com.trading.bot.engine

import com.trading.bot.domain.TradingDay
import com.trading.bot.domain.TradingState
import com.trading.bot.exchange.TradingCalendar
import com.trading.bot.exchange.UpbitTradingCalendar
import com.trading.common.config.TradingProperties
import com.trading.common.strategy.ExitGates
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap

class DailyResetManager(
    private val tradingProperties: TradingProperties,
    private val clock: Clock = Clock.system(TradingDay.KST),
    private val userId: Long? = null,
    private val calendar: TradingCalendar = UpbitTradingCalendar,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // 종목마다 다른 코루틴이 판정해도 안전하게 동시 맵을 쓴다.
    private val overrunWarnedBuyDate = ConcurrentHashMap<String, LocalDate>()

    fun getTradingDate(): LocalDate = calendar.tradingDate(clock)

    /** 보유 일수 상한은 진입 시점 스냅샷을 따른다. 스냅샷이 없으면 전역 설정값을 쓴다. */
    fun shouldSellForDailyReset(state: TradingState): Boolean {
        if (!state.position) return false
        val buyDate = state.buyDate ?: return false
        val configured = state.exitParamsOrGlobal(tradingProperties).maxHoldDays
        val holdLimit = ExitGates.effectiveMaxHoldDays(configured)
        val heldDays = calendar.tradingDaysBetween(buyDate, getTradingDate())
        if (heldDays > holdLimit) warnOverrunOnce(state.ticker, buyDate, heldDays, holdLimit)
        return heldDays >= holdLimit
    }

    // 매도가 나갈 때까지 매 tick 다시 판정되므로 같은 포지션에는 한 번만 남긴다.
    private fun warnOverrunOnce(ticker: String, buyDate: LocalDate, heldDays: Long, holdLimit: Int) {
        if (overrunWarnedBuyDate[ticker] == buyDate) return
        overrunWarnedBuyDate[ticker] = buyDate
        log.warn(
            "Hold limit overrun: {} held {} trading days (limit {}, buyDate={}, tradingDate={}, user {}) — daily reset fired late (#131)",
            ticker, heldDays, holdLimit, buyDate, getTradingDate(), userId,
        )
    }
}
