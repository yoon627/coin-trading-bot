package com.trading.bot.engine

import com.trading.bot.domain.Account
import com.trading.bot.domain.TradingState
import com.trading.bot.exchange.UpbitMarketRules

/** 잔고 비교 허용 오차. 거래소 수량은 소수 8자리까지다. */
internal const val VOLUME_EPSILON = 1e-8

/** Upbit 기준의 dust 판정. 엔진 경로는 주입된 MarketRules 를 쓴다. */
internal fun isBelowMinOrder(volume: Double, price: Double): Boolean = UpbitMarketRules.isBelowMinOrder(volume, price)

/**
 * 보유 수량 = free + min(locked, 우리 매도 주문이 잠글 수 있는 몫 중 아직 free 로 안 돌아온 양).
 * Upbit 의 locked 에는 출금 대기나 수동 주문도 섞여 있어 그대로 더하면 팔 수 없는 수량을 보유로 센다.
 */
internal fun heldVolume(account: Account, ourLockCeiling: Double): Double {
    val free = account.balanceDouble()
    return free + minOf(account.lockedDouble(), (ourLockCeiling - free).coerceAtLeast(0.0))
}

/**
 * 우리 매도 주문이 잠글 수 있는 최대 수량. 주문 수량을 모르는 옛 기록이면 상한을 두지 않는다.
 * 부분체결로 줄지 않는 값이라 체결분을 아는 호출부는 그만큼 빼서 넘긴다.
 */
internal fun ourSellLockCeiling(state: TradingState): Double =
    if (!state.hasPendingSell()) 0.0 else state.pendingSellVolume ?: Double.POSITIVE_INFINITY

/** 우리 매도 주문으로 설명되지 않는 lock 만 있는지. 출금 대기나 수동 주문이라 팔 수 없다. */
internal fun isUnattributableLock(account: Account, state: TradingState): Boolean =
    account.lockedDouble() > 0.0 && heldVolume(account, ourSellLockCeiling(state)) <= 0.0
