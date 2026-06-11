package com.trading.common.strategy

/** 청산 게이트 판정 — 라이브(PositionManager)와 백테스트(BacktestEngine)가 같은 조건식을 쓰도록 공용화. */
object ExitGates {

    /**
     * 트레일링 스탑 발동 판정.
     *
     * - `pnlPct > 0`: 수익 중일 때만 (손실 구간은 손절 담당 — 기존 게이트 유지)
     * - `peakPnlPct >= trailingArmPct`: 고점 수익률이 arm 임계 도달 후에만. pnl>0 ∧ drop≥trail 이면
     *   peakPnl > trail/(1−trail/100) 이 강제되므로 arm 은 trailingStopPct 보다 클 때만 실효한다.
     * - NaN 입력(평단 0 유래 등 비정상)은 IEEE 비교 의미상 모든 조건이 false → 발동 안 함.
     */
    fun isTrailingStopTriggered(
        pnlPct: Double,
        peakPnlPct: Double,
        dropFromPeakPct: Double,
        trailingStopPct: Double,
        trailingArmPct: Double,
    ): Boolean = pnlPct > 0.0 && peakPnlPct >= trailingArmPct && dropFromPeakPct >= trailingStopPct
}
