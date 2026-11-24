package com.trading.bot.engine

import com.trading.bot.domain.ExitParamsSnapshot
import com.trading.bot.domain.TradingState
import com.trading.common.config.TradingProperties

/** 엔진 설정(env 위에 사용자 매매 값을 덮은 것)의 청산 파라미터. 새 진입이 스냅샷으로 저장하고, 사용자가 값을 저장하면 보유 포지션 스냅샷도 이 값으로 바뀐다. */
internal fun TradingProperties.exitParamsSnapshot(): ExitParamsSnapshot = ExitParamsSnapshot(
    takeProfitPct = takeProfitPct,
    maxLossPct = maxLossPct,
    trailingStopPct = trailingStopPct,
    trailingArmPct = trailingArmPct,
    maxHoldDays = maxHoldDays,
)

/**
 * 포지션에 적용할 청산 파라미터. 스냅샷(진입 때 값, 또는 사용자가 값을 저장할 때 덮은 값)이 있으면 그것을, 없으면 엔진 설정을 쓴다.
 * env 를 바꾼 재배포는 보유 포지션에 닿지 않게 하려는 것이라, 청산 판정은 모두 이 함수를 거친다.
 * `maxHoldDays` 는 보정 전 값이라 판정할 때 `ExitGates.effectiveMaxHoldDays` 를 거친다.
 */
internal fun TradingState.exitParamsOrGlobal(global: TradingProperties): ExitParamsSnapshot =
    exitParams ?: global.exitParamsSnapshot()
