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
    val createdAt: LocalDateTime = LocalDateTime.now(),
)
