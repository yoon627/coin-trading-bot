package com.trading.bot.engine

import com.trading.bot.domain.Account
import com.trading.bot.domain.TradingState
import com.trading.common.strategy.AccumulateLadder

// 잔고·보유 해석 — 상태를 바꾸지 않는 판정이라 top-level 에 둬 PositionManager 밖의 협력 객체도 쓴다.

/** 잔고 비교의 허용 오차 — 거래소 수량 소수 8자리의 한 단위(1e-8), 부동소수 오차를 흡수한다. */
internal const val VOLUME_EPSILON = 1e-8

/**
 * 이 수량을 이 가격에 팔면 거래소 최소주문(원화 5,000원)에 못 미치는가 — 그런 보유는 팔 수 없는 dust 다(#234).
 * 정확히 최소주문이면 팔 수 있다. 엔진 테스트는 PositionManager 를 mock 하므로 판정은 멤버가 아니라 여기 둔다.
 */
internal fun isBelowMinOrder(volume: Double, price: Double): Boolean = volume * price < AccumulateLadder.MIN_ORDER_KRW

/**
 * 기록상 보유가 이 가격에서 팔 수 없는 dust 인가. 기록(`holdVolume`)은 마지막 동기화 값이라 진입 게이트를 여는 데만 쓰고,
 * 주문은 실잔고로 다시 판정한다. 수량 미상(0)은 dust 로 보지 않는다 — 모르면 관리하는 쪽이 안전하다.
 */
internal fun TradingState.isDustAt(price: Double): Boolean = position && holdVolume > 0.0 && isBelowMinOrder(holdVolume, price)

/**
 * 보유 수량 = `free + min(locked, 우리 매도 주문이 아직 잠그고 있을 수 있는 최대 수량)`.
 *
 * Upbit 의 `locked` 는 "출금이나 주문 등에 잠겨 있는 잔액"이라 우리 주문 외 사유가 섞인다. 그대로 더하면
 * `sell()` 이 주문할 수 없는(free 만 판다) 수량을 보유로 세어 유령 포지션이 남고, 반대로 통째로 버리면
 * 우리 주문이 방금 풀렸지만 아직 free 로 안 돌아온 잔량을 잃는다. 상한을 두면 양쪽을 다 피하고,
 * 거래소가 locked→free 를 언제 반영하든 같은 답이 나온다.
 *
 * @param ourLockCeiling 우리 매도 주문이 잠그고 있을 수 있는 **최대** 수량. 이미 free 로 돌아온 몫은
 *   상한에서 빠진다 — 그래서 주문이 이미 풀렸으면 상한이 0 이 되어 타 사유 locked 가 새지 않는다.
 */
internal fun heldVolume(account: Account, ourLockCeiling: Double): Double {
    val free = account.balanceDouble()
    return free + minOf(account.lockedDouble(), (ourLockCeiling - free).coerceAtLeast(0.0))
}

/**
 * 우리 매도 주문이 잠그고 있을 수 있는 최대 수량 = 주문 수량. 미해소 주문이 없으면 0, 주문은 있는데
 * 수량 기록이 없으면(레거시 durable row) 상한을 걸지 않는다 — 그 경우 [heldVolume] 은 종전대로
 * free+locked 가 된다.
 *
 * **느슨한 상한이다.** `pendingSellVolume` 은 주문 시점 수량이고 부분체결로 줄지 않으므로, 주문이 `wait`
 * 로 남아 일부만 체결된 구간에서는 체결분만큼 여유가 생긴다. 체결분을 아는 호출부는 그만큼 빼서 넘긴다.
 */
internal fun ourSellLockCeiling(state: TradingState): Double =
    if (!state.hasPendingSell()) 0.0 else state.pendingSellVolume ?: Double.POSITIVE_INFINITY

/** 우리 매도 주문으로 설명되지 않는 lock — 출금 대기이거나 사용자가 직접 낸 주문이다(팔 수 없으니 보유가 아니다). */
internal fun isUnattributableLock(account: Account, state: TradingState): Boolean =
    account.lockedDouble() > 0.0 && heldVolume(account, ourSellLockCeiling(state)) <= 0.0
