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
    private val userId: Long? = null,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private var lastResetDate: LocalDate? = null
    private val overrunWarnedBuyDate = mutableMapOf<String, LocalDate>()

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

    /**
     * 보유상한도 **진입 시점 스냅샷**을 따른다(#177). 보유 중 `maxHoldDays` 를 바꿔도 이미 열린 포지션은
     * 진입 때 규칙으로 청산된다 — 스냅샷이 없으면(이전 포지션·복원 실패) 전역값으로 폴백해 기존 동작을 보존한다.
     */
    fun shouldSellForDailyReset(state: TradingState): Boolean {
        if (!state.position) return false
        val buyDate = state.buyDate ?: return false
        val configured = state.exitParams?.maxHoldDays ?: tradingProperties.maxHoldDays
        val holdLimit = ExitGates.effectiveMaxHoldDays(configured)
        val heldDays = ChronoUnit.DAYS.between(buyDate, getTradingDate())
        if (heldDays > holdLimit) warnOverrunOnce(state.ticker, buyDate, heldDays, holdLimit)
        return heldDays >= holdLimit
    }

    // 매도 주문이 나갈 때까지(앞선 게이트가 먼저 걸리지 않는 동안) 매 tick 재판정되므로
    // 같은 포지션(buyDate)에는 프로세스 수명 동안 한 번만 남긴다. 원인은 여기서 알 수 없어 관측값만 적는다.
    private fun warnOverrunOnce(ticker: String, buyDate: LocalDate, heldDays: Long, holdLimit: Int) {
        if (overrunWarnedBuyDate[ticker] == buyDate) return
        overrunWarnedBuyDate[ticker] = buyDate
        log.warn(
            "Hold limit overrun: {} held {} trading days (limit {}, buyDate={}, tradingDate={}, user {}) — daily reset fired late (#131)",
            ticker, heldDays, holdLimit, buyDate, getTradingDate(), userId,
        )
    }
}
