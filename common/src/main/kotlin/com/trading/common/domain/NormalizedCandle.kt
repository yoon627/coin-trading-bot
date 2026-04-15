package com.trading.common.domain

import java.time.Instant

data class NormalizedCandle(
    val exchange: Exchange,
    val market: String,
    val openPrice: Double,
    val highPrice: Double,
    val lowPrice: Double,
    val closePrice: Double,
    val volume: Double,
    val quoteVolume: Double = 0.0,
    val interval: CandleInterval = CandleInterval.D1,
    val openTime: Instant = Instant.now(),
    val closeTime: Instant = Instant.now(),
)
