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
    val createdAt: LocalDateTime = LocalDateTime.now(),
)

enum class TradeSide {
    BUY, SELL
}

enum class SellReason {
    TAKE_PROFIT,
    STOP_LOSS,
    DAILY_RESET,
    MANUAL,
}
