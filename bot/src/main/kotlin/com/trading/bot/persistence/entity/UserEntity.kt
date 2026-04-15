package com.trading.bot.persistence.entity

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.LocalDateTime

@Table("users")
data class UserEntity(
    @Id
    val id: Long? = null,
    val username: String,
    val password: String,
    val upbitAccessKey: String? = null,
    val upbitSecretKey: String? = null,
    val publicProfile: Boolean = false,
    val publicStrategy: Boolean = false,
    val discordWebhookUrl: String? = null,
    val admin: Boolean = false,
    val createdAt: LocalDateTime = LocalDateTime.now(),
)
