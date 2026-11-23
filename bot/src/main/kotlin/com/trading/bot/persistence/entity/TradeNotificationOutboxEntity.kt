package com.trading.bot.persistence.entity

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

/** 체결 알림 한 건. [status] 는 PENDING·SENT·FAILED·SKIPPED 이다. */
@Table("trade_notification_outbox")
data class TradeNotificationOutboxEntity(
    @Id val id: Long? = null,
    val userId: Long,
    val exchange: String,
    val payload: String,
    val status: String = PENDING,
    val attempts: Int = 0,
    val nextAttemptAt: Instant,
    val lastError: String? = null,
    val createdAt: Instant,
    val sentAt: Instant? = null,
    /**
     * 같은 체결 트랜잭션에서 넣은 orders 줄. 이 이미지는 늘 채운다. null 은 V43 이전 이미지가 orders 쓰기 충돌 때 넣은 줄이다 —
     * 읽을 때 깨지지 않게 nullable 로 둔다.
     */
    val orderId: Long? = null,
) {
    companion object {
        const val PENDING = "PENDING"
        const val SENT = "SENT"
        const val FAILED = "FAILED"
        const val SKIPPED = "SKIPPED"
    }
}
