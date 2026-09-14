package com.trading.bot.persistence.entity

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.LocalDateTime

@Table("trade_records")
data class TradeRecordEntity(
    @Id
    val id: Long? = null,
    val ticker: String,
    val side: String,
    val price: Double,
    val volume: Double,
    val totalAmount: Double,
    // 이 주문의 실체결 대금(V26). NULL = 미상(V26 이전 행·주문 응답 없는 경로) — 추정값을 넣지 않는다.
    val orderAmount: Double? = null,
    val pnlPercent: Double? = null,
    val pnlAmount: Double? = null,
    val reason: String? = null,
    val strategy: String? = null,
    val userId: Long? = null,
    val createdAt: LocalDateTime = LocalDateTime.now(),
)
