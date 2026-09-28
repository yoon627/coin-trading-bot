package com.trading.bot.domain

/**
 * 진입 시점 청산 파라미터 스냅샷. 보유 중 전역 설정이 바뀌어도 기존 포지션을 진입 시점 기준으로 청산하기 위한 것.
 * 익절·손절·트레일링·보유상한이 engine 의 `exitParamsOrGlobal` 로 이 값(없으면 전역)을 읽는다.
 */
data class ExitParamsSnapshot(
    val takeProfitPct: Double,
    val maxLossPct: Double,
    val trailingStopPct: Double,
    val trailingArmPct: Double,
    val maxHoldDays: Int,
)
