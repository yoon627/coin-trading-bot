package com.trading.bot.engine

import com.trading.bot.domain.ExitParamsSnapshot
import com.trading.bot.domain.TradingState
import com.trading.common.config.TradingProperties

/** 지금 전역 설정으로 찍은 청산 파라미터 — 새 진입이 스냅샷으로 가져가고, 스냅샷 없는 포지션의 폴백도 이 값이다. */
internal fun TradingProperties.exitParamsSnapshot(): ExitParamsSnapshot = ExitParamsSnapshot(
    takeProfitPct = takeProfitPct,
    maxLossPct = maxLossPct,
    trailingStopPct = trailingStopPct,
    trailingArmPct = trailingArmPct,
    maxHoldDays = maxHoldDays,
)

/**
 * 이 포지션에 적용할 청산 파라미터 — **진입 시점 스냅샷**이 있으면 그것, 없으면 현재 전역값.
 *
 * 보유 중 전역 설정이 바뀌어도 그 포지션의 규칙은 진입 때 그대로다. 그러지 않으면 진입은 옛 규칙,
 * 청산은 새 규칙인 거래가 생겨 **성과 귀속이 깨진다**(2026-09-06 트레일링 승격에서 실제로 발생, #177).
 * 익절·손절·트레일링과 보유상한이 모두 이 함수를 읽는다 — 폴백이 둘로 갈라지면 한쪽만 새 규칙을 따르는 포지션이 생긴다.
 *
 * 폴백이 전역인 이유: 스냅샷이 없는 포지션(스냅샷 도입 이전·복원 실패·봇 밖 보유의 편입·무산된 dust 흡수)은 기존 동작을 그대로 둔다.
 * `maxHoldDays` 는 보정 전 값이다 — 판정은 `ExitGates.effectiveMaxHoldDays` 를 거친다.
 * `chartExitEnabled` 는 스냅샷에 없다 — 임계가 아니라 모드 스위치라 전역이 소유한다.
 */
internal fun TradingState.exitParamsOrGlobal(global: TradingProperties): ExitParamsSnapshot =
    exitParams ?: global.exitParamsSnapshot()
