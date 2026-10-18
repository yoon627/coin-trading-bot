package com.trading.bot.engine

import com.trading.bot.domain.ExitParamsSnapshot
import com.trading.bot.domain.TradingState
import com.trading.common.config.TradingProperties

/** 현재 전역 설정의 청산 파라미터. 새 진입이 스냅샷으로 저장한다. */
internal fun TradingProperties.exitParamsSnapshot(): ExitParamsSnapshot = ExitParamsSnapshot(
    takeProfitPct = takeProfitPct,
    maxLossPct = maxLossPct,
    trailingStopPct = trailingStopPct,
    trailingArmPct = trailingArmPct,
    maxHoldDays = maxHoldDays,
)

/**
 * 포지션에 적용할 청산 파라미터. 진입 시점 스냅샷이 있으면 그것을, 없으면 현재 전역값을 쓴다.
 * 보유 중 설정을 바꿔도 진입과 청산이 같은 규칙을 따르게 하려는 것이라, 청산 판정은 모두 이 함수를 거친다.
 * `maxHoldDays` 는 보정 전 값이라 판정할 때 `ExitGates.effectiveMaxHoldDays` 를 거친다.
 */
internal fun TradingState.exitParamsOrGlobal(global: TradingProperties): ExitParamsSnapshot =
    exitParams ?: global.exitParamsSnapshot()
