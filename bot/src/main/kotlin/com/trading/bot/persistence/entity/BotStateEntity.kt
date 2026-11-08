package com.trading.bot.persistence.entity

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant
import java.time.LocalDateTime

@Table("bot_state")
data class BotStateEntity(
    @Id
    val id: Long? = null,
    val userId: Long,
    val exchange: String = "UPBIT",
    val running: Boolean = false,
    val strategy: String = "combined",
    val tickers: String = "KRW-BTC",
    val updatedAt: LocalDateTime = LocalDateTime.now(),
    /** 켜져 있으면 엔진은 BUY 만 내지 않는다. 매도는 그대로다. */
    val entriesPaused: Boolean = false,
    val entriesPausedChangedAt: Instant? = null,
)
