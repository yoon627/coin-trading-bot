package com.trading.bot.domain

import java.time.LocalDateTime

data class TradeRecord(
    val ticker: String,
    val side: TradeSide,
    val price: Double,
    val volume: Double,
    val totalAmount: Double,
    val pnlPercent: Double? = null,
    val reason: String? = null,
    val strategy: String? = null,
    // 거래소 주문 uuid — 재시작 후 reconcile 중복 기록을 막는 멱등 dedup 키(#20). null 이면 dedup 대상 아님.
    val exchangeOrderId: String? = null,
    val userId: Long = 0,
    val createdAt: LocalDateTime = LocalDateTime.now(),
)

enum class TradeSide {
    BUY, SELL
}

enum class SellReason {
    TAKE_PROFIT,
    TRAILING_STOP,
    STOP_LOSS,
    CHART_EXIT,
    DAILY_RESET,
    MANUAL,
}
