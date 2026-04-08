package com.trading.bot.persistence.entity

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.LocalDateTime

@Table("trade_records")
data class TradeRecordEntity(
    @Id
    val id: Long? = null,
    val ticker: String,
    val side: String,
    val price: Double,
    val volume: Double,
    val totalAmount: Double,
    val pnlPercent: Double? = null,
    val reason: String? = null,
    val strategy: String? = null,
    val createdAt: LocalDateTime = LocalDateTime.now(),
)
