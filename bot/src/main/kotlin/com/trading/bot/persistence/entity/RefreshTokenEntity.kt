package com.trading.bot.persistence.entity

import java.time.Instant
import java.util.UUID
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table

@Table("refresh_tokens")
data class RefreshTokenEntity(
    @Id val id: Long? = null,
    val userId: Long,
    val familyId: UUID,
    val tokenHash: String,
    val remember: Boolean,
    val createdAt: Instant? = null,
    val expiresAt: Instant,
    val replacedAt: Instant? = null,
    val revokedAt: Instant? = null,
)
