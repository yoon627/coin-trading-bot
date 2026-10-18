package com.trading.bot.domain

/** 진입 시점의 청산 파라미터. 보유 중 전역 설정이 바뀌어도 기존 포지션은 진입 시점 값으로 청산한다. */
data class ExitParamsSnapshot(
    val takeProfitPct: Double,
    val maxLossPct: Double,
    val trailingStopPct: Double,
    val trailingArmPct: Double,
    val maxHoldDays: Int,
)
