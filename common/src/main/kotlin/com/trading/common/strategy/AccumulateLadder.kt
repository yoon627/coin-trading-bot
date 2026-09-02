package com.trading.common.strategy

import kotlin.math.max

/**
 * 적립 사다리 파라미터. `rungAmountKrw` 가 거래소 최소주문(5,000원) 미만이면 어떤 단도 체결될 수 없으므로
 * 생성 시점에 거부한다 — 기동 후 매 tick 주문 거부 로그로 드러나는 것보다 낫다.
 */
data class LadderParams(
    val budgetKrw: Double,
    val maxRungs: Int,
    val stepDownPct: Double,
    val stepUpPct: Double,
) {
    init {
        require(maxRungs >= 1) { "maxRungs must be >= 1, got $maxRungs" }
        require(budgetKrw > 0.0) { "budgetKrw must be > 0, got $budgetKrw" }
        require(stepDownPct > 0.0) { "stepDownPct must be > 0, got $stepDownPct" }
        require(stepUpPct > 0.0) { "stepUpPct must be > 0, got $stepUpPct" }
        require(budgetKrw / maxRungs >= AccumulateLadder.MIN_ORDER_KRW) {
            "budgetKrw / maxRungs must be >= ${AccumulateLadder.MIN_ORDER_KRW} KRW (Upbit minimum order), got ${budgetKrw / maxRungs}"
        }
    }

    val rungAmountKrw: Double get() = budgetKrw / maxRungs
}

/**
 * 사다리 판정 입력. 라이브는 `TradingState` 에서, 백테는 시뮬레이션 상태에서 매핑한다 — 이 타입이
 * `common` 에 있어야 두 쪽이 같은 판정식을 쓴다(`ExitGates` 와 같은 이유).
 *
 * @param lastActionPrice 마지막 매수/매도의 **트리거가**(체결가가 아니다 — 거래소는 누적 평단만 준다).
 * @param flatPeak 무포지션 구간에서 관측한 최고가. 0 = 아직 관측 없음.
 */
data class LadderInput(
    val rungsFilled: Int,
    val lastActionPrice: Double,
    val flatPeak: Double,
    val avgBuyPrice: Double,
    val holdVolume: Double,
    val price: Double,
)

sealed interface LadderAction {
    data class Buy(val amountKrw: Double, val triggerPrice: Double) : LadderAction

    /** @param isFinal 마지막 단 — 호출부는 분할 수량 대신 거래소 잔고 전량을 주문한다. */
    data class Sell(val volume: Double, val triggerPrice: Double, val isFinal: Boolean) : LadderAction

    data object Hold : LadderAction
}

/**
 * "떨어지면 더 사고, 오르면 나눠 판다" 사다리의 판정식. 손절·익절·트레일링·보유상한은 없다 —
 * 리스크 상한은 예산(`budgetKrw`) 하나이고, 그 판정은 rung 수가 아니라 실측 원가(`avgBuyPrice × holdVolume`)로 한다.
 * rung 은 매도 분할 단위만 담당한다.
 */
object AccumulateLadder {
    const val MIN_ORDER_KRW = 5_000.0

    /** 기록·집계의 `strategy` 귀속명. 스윙 전략 집계와 섞이지 않게 별도 이름을 쓴다. */
    const val STRATEGY_NAME = "accumulate"

    // 원가 합산의 부동소수 오차 흡수 — 1원 미만 차이로 마지막 단이 막히지 않게.
    private const val BUDGET_TOLERANCE_KRW = 1.0

    fun decide(input: LadderInput, params: LadderParams): LadderAction {
        val price = input.price
        if (price <= 0.0) return LadderAction.Hold

        val hasBalance = input.holdVolume > 0.0
        val hasRungs = input.rungsFilled > 0
        return when {
            // 장부(rung)와 잔고가 어긋난 상태 — 정합은 호출부 매퍼의 몫이고 여기서는 거래하지 않는다.
            hasBalance != hasRungs -> LadderAction.Hold
            !hasBalance -> decideEntry(input, params)
            else -> decideSell(input, params) ?: decideAddRung(input, params) ?: LadderAction.Hold
        }
    }

    /**
     * 이 가격 이하에서 매수가 트리거된다(예산·단 수 게이트는 [decide] 가 본다). null = 매수 기준 없음.
     * 백테가 봉의 low 와 비교해 "봉 안에서 닿았는가"를 판정하는 데 쓴다 — 임계식을 밖에서 다시 적지 않기 위해 노출.
     */
    fun buyTriggerPrice(input: LadderInput, params: LadderParams): Double? {
        val reference = if (input.rungsFilled > 0) input.lastActionPrice else input.flatPeak
        if (reference <= 0.0) return null
        return reference * (1 - params.stepDownPct / 100.0)
    }

    /** 이 가격 이상에서 매도가 트리거된다. null = 보유 없음. */
    fun sellTriggerPrice(input: LadderInput, params: LadderParams): Double? {
        if (input.rungsFilled <= 0) return null
        val reference = max(input.avgBuyPrice, input.lastActionPrice)
        if (reference <= 0.0) return null
        return reference * (1 + params.stepUpPct / 100.0)
    }

    private fun decideEntry(input: LadderInput, params: LadderParams): LadderAction {
        val trigger = buyTriggerPrice(input, params) ?: return LadderAction.Hold
        return if (input.price <= trigger) LadderAction.Buy(params.rungAmountKrw, input.price) else LadderAction.Hold
    }

    private fun decideSell(input: LadderInput, params: LadderParams): LadderAction? {
        val trigger = sellTriggerPrice(input, params) ?: return null
        if (input.price < trigger) return null
        val isFinal = input.rungsFilled == 1
        val volume = if (isFinal) input.holdVolume else input.holdVolume / input.rungsFilled
        // 최소주문 미만은 거래소가 거부한다 — rung 을 소모하지 않고 다음 기회를 기다린다.
        if (volume * input.price < MIN_ORDER_KRW) return LadderAction.Hold
        return LadderAction.Sell(volume, input.price, isFinal)
    }

    private fun decideAddRung(input: LadderInput, params: LadderParams): LadderAction? {
        if (input.rungsFilled >= params.maxRungs) return null
        val trigger = buyTriggerPrice(input, params) ?: return null
        if (input.price > trigger) return null
        val investedKrw = input.avgBuyPrice * input.holdVolume
        if (investedKrw + params.rungAmountKrw > params.budgetKrw + BUDGET_TOLERANCE_KRW) return null
        return LadderAction.Buy(params.rungAmountKrw, input.price)
    }
}
