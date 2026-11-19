package com.trading.bot.exchange

import java.time.Duration

/**
 * 응답을 못 받은 주문을 대사로 확정하는 조건.
 * - 잔고 흔적이 없으면 연속 [notPlacedMisses] 번 못 찾고 처음 못 찾은 뒤 [notPlacedAfter] 가 지나야 미접수다.
 * - 잔고 흔적을 본 주문은 첫 흔적 뒤 [tracedNotPlacedAfter] 가 지난 404 에서 종료 주문 목록을 다시 보고, 거기에도 없으면 미접수다.
 * [supportsClientOrderId] 가 false 인 거래소는 "못 찾음" 이 미접수의 증거가 아니라서 엔진을 만들지 않는다.
 */
data class UnknownOrderPolicy(
    val notPlacedMisses: Int,
    val notPlacedAfter: Duration,
    val supportsClientOrderId: Boolean,
    val tracedNotPlacedAfter: Duration,
) {
    init {
        require(notPlacedMisses >= 1) { "notPlacedMisses must be >= 1: $notPlacedMisses" }
        require(!notPlacedAfter.isNegative) { "notPlacedAfter must not be negative: $notPlacedAfter" }
        require(!tracedNotPlacedAfter.isNegative) { "tracedNotPlacedAfter must not be negative: $tracedNotPlacedAfter" }
    }

    companion object {
        val UPBIT = UnknownOrderPolicy(
            notPlacedMisses = 2,
            notPlacedAfter = Duration.ofSeconds(60),
            supportsClientOrderId = true,
            tracedNotPlacedAfter = Duration.ofMinutes(30),
        )
    }
}
