package com.trading.bot.persistence.entity

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.LocalDateTime

@Table("price_snapshots")
data class PriceSnapshotEntity(
    @Id
    val id: Long? = null,
    val ticker: String,
    val price: Double,
    val highPrice: Double,
    val lowPrice: Double,
    val tradeVolume: Double,
    @org.springframework.data.relational.core.mapping.Column("acc_trade_price_24h")
    val accTradePrice24h: Double,
    val signedChangeRate: Double,
    val capturedAt: LocalDateTime = LocalDateTime.now(),
)
