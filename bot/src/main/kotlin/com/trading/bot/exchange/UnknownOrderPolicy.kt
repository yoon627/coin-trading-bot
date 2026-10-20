package com.trading.bot.exchange

import java.time.Duration

/**
 * 응답을 못 받은 주문을 미접수로 확정하는 조건. 연속 [notPlacedMisses] 번 못 찾고 처음 못 찾은 뒤 [notPlacedAfter] 가 지나야 한다.
 * [supportsClientOrderId] 가 false 인 거래소는 "못 찾음" 이 미접수의 증거가 아니라서 미접수 확정을 하지 않는다.
 */
data class UnknownOrderPolicy(
    val notPlacedMisses: Int,
    val notPlacedAfter: Duration,
    val supportsClientOrderId: Boolean,
) {
    init {
        require(notPlacedMisses >= 1) { "notPlacedMisses must be >= 1: $notPlacedMisses" }
        require(!notPlacedAfter.isNegative) { "notPlacedAfter must not be negative: $notPlacedAfter" }
    }

    companion object {
        val UPBIT = UnknownOrderPolicy(notPlacedMisses = 2, notPlacedAfter = Duration.ofSeconds(60), supportsClientOrderId = true)
    }
}
