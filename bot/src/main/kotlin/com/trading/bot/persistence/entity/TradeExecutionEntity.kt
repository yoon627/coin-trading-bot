package com.trading.bot.persistence.entity

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

@Table("trade_executions")
data class TradeExecutionEntity(
    @Id val id: Long? = null,
    val userId: Long,
    val exchange: String,
    val market: String,
    val side: String,
    val price: Double,
    val volume: Double,
    val totalAmount: Double,
    val fee: Double = 0.0,
    val pnlPercent: Double? = null,
    val pnlAmount: Double? = null,
    val reason: String? = null,
    val strategy: String? = null,
    val exchangeOrderId: String? = null,
    val executedAt: Instant = Instant.now(),
    val createdAt: Instant = Instant.now(),
)
