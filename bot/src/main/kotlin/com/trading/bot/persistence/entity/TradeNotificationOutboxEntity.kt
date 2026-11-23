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
    /** 같은 체결 트랜잭션에서 넣은 orders 줄. 넣지 못했으면 null 이다(V42). */
    val orderId: Long? = null,
) {
    companion object {
        const val PENDING = "PENDING"
        const val SENT = "SENT"
        const val FAILED = "FAILED"
        const val SKIPPED = "SKIPPED"
    }
}
