package com.trading.common.domain

import java.time.Instant

data class NormalizedTicker(
    val exchange: Exchange,
    val market: String,
    val price: Double,
    val bidPrice: Double = 0.0,
    val askPrice: Double = 0.0,
    val volume24h: Double = 0.0,
    val quoteVolume24h: Double = 0.0,
    val changeRate24h: Double = 0.0,
    val highPrice24h: Double = 0.0,
    val lowPrice24h: Double = 0.0,
    val timestamp: Instant = Instant.now(),
)
