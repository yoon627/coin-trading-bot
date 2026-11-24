package com.trading.bot.engine

import com.trading.bot.domain.ExitParamsSnapshot
import com.trading.bot.domain.TradingState
import com.trading.bot.domain.UserTradingParams
import java.security.MessageDigest
import com.trading.common.strategy.ExitGates

/**
 * 매매 값을 저장했을 때 보유 포지션 하나가 받는 변화. [triggers] 는 지금 가격([price])에서 새 값으로 곧바로 걸리는 청산 —
 * `take_profit`·`stop_loss`·`trailing_stop`·`max_hold_days` — 이다. 가격을 모르면 비어 있고 [pnlPct] 도 null 이다.
 */
data class PositionChange(
    val ticker: String,
    val before: ExitParamsSnapshot,
    val after: ExitParamsSnapshot,
    val price: Double?,
    val pnlPct: Double?,
    val triggers: List<String>,
)

/** 청산 판정은 PositionManager·DailyResetManager 와 같은 식(수수료 전 수익률, [ExitGates])이다. */
internal object ParamsPreview {

    /**
     * 값 저장이 청산 스냅샷을 바꾸는 포지션 — 미리보기와 실제 덮기가 이 한 규칙을 쓴다. 매도 중인 포지션은 이미 청산 중이라 두고,
     * 잔량을 놓아 준 행(보유 아님)은 엔진이 청산하지 않으므로 뺀다. 도는 엔진의 상태는 `position`, 저장된 상태는 장부 수량으로 본다.
     */
    fun isHeld(state: TradingState): Boolean =
        state.exitParams != null && !state.hasPendingSell() && (state.position || (state.storedBook?.volume ?: 0.0) > 0.0)

    /** [state] 가 보유가 아니거나 스냅샷이 [after] 와 같으면 null. [heldDays] 는 매수 뒤 지난 거래일 수(모르면 null). */
    fun change(state: TradingState, after: ExitParamsSnapshot, price: Double?, heldDays: Long?): PositionChange? {
        if (!isHeld(state)) return null
        val before = state.exitParams ?: return null
        if (before == after) return null
        // 멈춘 봇의 상태는 거래소 동기화 전이라 평단이 비어 있다 — 그때는 저장된 장부의 평단을 쓴다.
        val avg = state.avgBuyPrice.takeIf { it > 0 } ?: state.storedBook?.avgPrice?.takeIf { it > 0 }
        if (price == null || avg == null) return PositionChange(state.ticker, before, after, null, null, emptyList())
        val pnl = (price - avg) / avg * 100.0
        val peak = maxOf(state.peakPrice, price)
        val triggers = buildList {
            if (pnl >= after.takeProfitPct) add("take_profit")
            if (pnl <= -after.maxLossPct) add("stop_loss")
            val trailing = ExitGates.isTrailingStopTriggered(
                pnlPct = pnl,
                peakPnlPct = (peak - avg) / avg * 100.0,
                dropFromPeakPct = (peak - price) / peak * 100.0,
                trailingStopPct = after.trailingStopPct,
                trailingArmPct = after.trailingArmPct,
            )
            if (trailing) add("trailing_stop")
            if (heldDays != null && heldDays >= ExitGates.effectiveMaxHoldDays(after.maxHoldDays)) add("max_hold_days")
        }
        return PositionChange(state.ticker, before, after, price, pnl, triggers)
    }

    /**
     * 확인이 가리키는 내용 — 저장할 값과 포지션별 전→후. 화면이 미리본 뒤 값을 고치거나 그사이 포지션이 바뀌면 달라져, 서버가 다시
     * 확인을 받는다. 가격·수익률은 넣지 않는다(시세가 움직일 때마다 확인이 깨지면 저장할 수 없다).
     */
    fun confirmToken(params: UserTradingParams, changes: List<PositionChange>): String {
        val text = params.toString() + changes.sortedBy { it.ticker }.joinToString("|") { "${it.ticker}:${it.before}>${it.after}" }
        return MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
