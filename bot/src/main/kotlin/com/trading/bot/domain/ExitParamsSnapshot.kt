package com.trading.bot.domain

/**
 * 진입 시점 청산 파라미터 스냅샷. champion 파라미터가 바뀌어도 기존 포지션을 진입 시점 기준으로 청산하기 위한 것.
 * 이 PR 은 저장·복원까지만 담당하고, 실제 소비(exit gate 가 전역 대신 이 값을 읽음)는 strategy-evolution Phase 2.
 */
data class ExitParamsSnapshot(
    val takeProfitPct: Double,
    val maxLossPct: Double,
    val trailingStopPct: Double,
    val trailingArmPct: Double,
    val maxHoldDays: Int,
)
