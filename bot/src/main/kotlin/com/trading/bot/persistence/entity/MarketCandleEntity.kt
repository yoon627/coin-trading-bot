package com.trading.bot.persistence.entity

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

@Table("market_candles")
data class MarketCandleEntity(
    @Id val id: Long? = null,
    val exchange: String,
    val market: String,
    val intervalMinutes: Int,
    val openPrice: Double,
    val highPrice: Double,
    val lowPrice: Double,
    val closePrice: Double,
    val volume: Double,
    val quoteVolume: Double = 0.0,
    val openTime: Instant,
    val closeTime: Instant,
)
