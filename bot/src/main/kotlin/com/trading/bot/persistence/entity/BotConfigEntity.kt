package com.trading.bot.persistence.entity

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

@Table("bot_configs")
data class BotConfigEntity(
    @Id val id: Long? = null,
    val userId: Long,
    val exchange: String,
    val market: String,
    val strategy: String,
    val parameters: String = "{}",
    val enabled: Boolean = true,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
)
