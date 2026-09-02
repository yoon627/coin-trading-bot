package com.trading.bot.engine

import com.trading.bot.domain.TradingState
import com.trading.common.strategy.LadderInput
import com.trading.common.strategy.LadderParams
import kotlin.math.ceil

/**
 * `TradingState`(bot) ↔ `LadderInput`(common) 경계. 사다리 장부(rung·기준가)는 DB 에서, 잔고·평단은
 * 거래소에서 복원되므로 두 소스가 갈라질 수 있다 — 그 정합 규칙은 여기 한 곳에만 둔다.
 */
object LadderStateMapper {

    fun toInput(state: TradingState, price: Double): LadderInput = LadderInput(
        rungsFilled = state.rungsFilled,
        lastActionPrice = state.lastActionPrice,
        flatPeak = state.flatPeak,
        avgBuyPrice = state.avgBuyPrice,
        holdVolume = state.holdVolume,
        price = price,
    )

    /**
     * 복원·시딩 직후 **1회** 적용한다(호출부가 보장). 이후 tick 은 장부를 신뢰한다 — 매 tick 돌리면
     * 사람이 고친 값이 다시 덮인다.
     *
     * @return 장부를 고쳤으면 그 설명(WARN 용), 아니면 null. flatPeak 초기화는 정상 경로라 null.
     */
    fun reconcile(state: TradingState, params: LadderParams, price: Double): String? {
        val holding = state.holdVolume > 0.0
        return when {
            holding && state.rungsFilled == 0 -> {
                // 스윙(또는 수동)으로 잡은 보유를 사다리로 편입 — 실측 원가를 단당 금액으로 나눈 만큼 채운 것으로 본다.
                val investedKrw = state.avgBuyPrice * state.holdVolume
                state.rungsFilled = ceil(investedKrw / params.rungAmountKrw).toInt().coerceIn(1, params.maxRungs)
                state.lastActionPrice = state.avgBuyPrice
                "기존 보유를 사다리로 편입: 원가 %.0f원 → %d단, 기준가=평단 %.2f".format(investedKrw, state.rungsFilled, state.avgBuyPrice)
            }
            !holding && state.rungsFilled > 0 -> {
                val had = state.rungsFilled
                state.rungsFilled = 0
                state.lastActionPrice = 0.0
                if (state.flatPeak <= 0.0) state.flatPeak = price
                "장부 ${had}단인데 거래소 잔고 없음 — 수동 청산으로 보고 사다리를 비운다"
            }
            else -> {
                if (!holding && state.flatPeak <= 0.0) state.flatPeak = price
                null
            }
        }
    }
}
