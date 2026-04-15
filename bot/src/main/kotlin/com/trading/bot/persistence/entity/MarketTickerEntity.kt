package com.trading.bot.persistence.entity

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

@Table("market_tickers")
data class MarketTickerEntity(
    @Id val id: Long? = null,
    val exchange: String,
    val market: String,
    val price: Double,
    val bidPrice: Double? = null,
    val askPrice: Double? = null,
    val volume24h: Double? = null,
    val quoteVolume24h: Double? = null,
    val changeRate24h: Double? = null,
    val highPrice24h: Double? = null,
    val lowPrice24h: Double? = null,
    val recordedAt: Instant = Instant.now(),
)
