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
    // [com.trading.common.domain.Exchange.name]. /api/trades 가 이 엔티티를 그대로 내보내는데, 응답은 요청한 한 거래소의 행뿐이라 이 열은 빼서 응답 형태를 유지한다.
    @get:JsonIgnore
    val exchange: String = "UPBIT",
)
