package com.trading.common.strategy

/** 라이브 청산과 그림자 관측이 같은 조건식을 쓰도록 모아 둔다. */
object ExitGates {

    /**
     * 손실 구간은 손절이 맡으므로 수익 중일 때만 발동한다. pnl > 0 이면서 drop >= trail 이면 고점이 이미 trail 보다
     * 높으므로 arm 은 trailingStopPct 보다 클 때만 효과가 있다. NaN 입력이면 모든 비교가 false 라 발동하지 않는다.
     */
    fun isTrailingStopTriggered(
        pnlPct: Double,
        peakPnlPct: Double,
        dropFromPeakPct: Double,
        trailingStopPct: Double,
        trailingArmPct: Double,
    ): Boolean = pnlPct > 0.0 && peakPnlPct >= trailingArmPct && dropFromPeakPct >= trailingStopPct

    /** 0 이하를 1 로 올려 매수 당일 바로 청산되는 반복을 막는다. ExitParamRanges 도 이 해석을 전제한다. */
    fun effectiveMaxHoldDays(maxHoldDays: Int): Int = maxHoldDays.coerceAtLeast(1)
}
