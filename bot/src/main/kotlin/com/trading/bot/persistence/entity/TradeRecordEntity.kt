package com.trading.bot.persistence.entity

import com.fasterxml.jackson.annotation.JsonIgnore
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
    // 실체결 대금. 모르면 추정하지 않고 null 로 둔다.
    val orderAmount: Double? = null,
    val pnlPercent: Double? = null,
    val pnlAmount: Double? = null,
    val reason: String? = null,
    val strategy: String? = null,
    val userId: Long? = null,
    val createdAt: LocalDateTime = LocalDateTime.now(),
    // [com.trading.common.domain.Exchange.name]. /api/trades 가 이 엔티티를 그대로 내보내므로 거래소 파라미터를 붙이기 전까지 응답에서 뺀다.
    @get:JsonIgnore
    val exchange: String = "UPBIT",
)
