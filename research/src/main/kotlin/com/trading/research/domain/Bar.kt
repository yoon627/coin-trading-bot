package com.trading.research.domain

import com.trading.common.domain.NormalizedCandle
import java.time.Instant

data class Bar(
    val openTime: Instant,
    val closeTime: Instant,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double,
    val quoteVolume: Double,
)

fun Bar.toNormalizedCandle(asset: Asset): NormalizedCandle = NormalizedCandle(
    exchange = asset.exchange,
    market = asset.market,
    openTime = openTime,
    closeTime = closeTime,
    openPrice = open,
    highPrice = high,
    lowPrice = low,
    closePrice = close,
    volume = volume,
    quoteVolume = quoteVolume,
)
