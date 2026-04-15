package com.trading.bot.persistence.entity

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

@Table("positions")
data class PositionEntity(
    @Id val id: Long? = null,
    val userId: Long,
    val exchange: String,
    val market: String,
    val avgBuyPrice: Double,
    val volume: Double,
    val peakPrice: Double = 0.0,
    val strategy: String? = null,
    val openedAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
)
