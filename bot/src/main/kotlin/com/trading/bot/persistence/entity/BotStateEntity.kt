package com.trading.bot.persistence.entity

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.LocalDateTime

@Table("bot_state")
data class BotStateEntity(
    @Id
    val id: Long? = null,
    val userId: Long,
    val exchange: String = "UPBIT", // (user_id, exchange) 별 1행 — Upbit/KIS 동시 운영 (V17)
    val running: Boolean = false,
    val strategy: String = "combined",
    val tickers: String = "KRW-BTC",
    val updatedAt: LocalDateTime = LocalDateTime.now(),
)
