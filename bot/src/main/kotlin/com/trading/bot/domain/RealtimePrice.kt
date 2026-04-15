package com.trading.bot.domain

data class RealtimePrice(
    val market: String,
    val tradePrice: Double,
    val signedChangeRate: Double,
    val accTradePrice24h: Double,
    val highPrice: Double = 0.0,
    val lowPrice: Double = 0.0,
    val timestamp: Long = System.currentTimeMillis(),
)
