package com.trading.bot.engine

/** 매수액이 최소 주문 검사에 걸려 매수를 건너뛰는 이유. */
internal enum class BuySkip { BELOW_MIN_ORDER, UNSELLABLE_AT_STOP_LOSS }

/**
 * 매수 직전([PositionManager])과 엔진 시작 경고([TradingEngine.startupConfigWarnings])가 함께 쓰는 판정.
 * 식을 두 벌 두면 한쪽만 바뀌었을 때 경고가 실제 매수 동작과 갈린다.
 */
internal fun buySkip(investAmount: Double, maxLossPct: Double, minOrderNotional: Double): BuySkip? = when {
    investAmount < minOrderNotional -> BuySkip.BELOW_MIN_ORDER
    // 손절가까지 내려가도 최소주문 이상이어야 손절할 수 있다.
    investAmount * (1 - maxLossPct / 100) < minOrderNotional -> BuySkip.UNSELLABLE_AT_STOP_LOSS
    else -> null
}
