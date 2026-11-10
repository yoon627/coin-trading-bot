package com.trading.bot.persistence.entity

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

/** 체결 알림 한 건. [status] 는 PENDING·SENT·FAILED·SKIPPED 이다. */
@Table("trade_notification_outbox")
data class TradeNotificationOutboxEntity(
    @Id val id: Long? = null,
    val tradeExecutionId: Long,
    val userId: Long,
    val exchange: String,
    val payload: String,
    val status: String = PENDING,
    val attempts: Int = 0,
    val nextAttemptAt: Instant,
    val lastError: String? = null,
    val createdAt: Instant,
    val sentAt: Instant? = null,
) {
    companion object {
        const val PENDING = "PENDING"
        const val SENT = "SENT"
        const val FAILED = "FAILED"
        const val SKIPPED = "SKIPPED"
    }
}
