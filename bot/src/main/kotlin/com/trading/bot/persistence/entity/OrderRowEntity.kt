package com.trading.bot.persistence.entity

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

/** `orders` 의 한 줄 = 봇이 보낸 주문 하나(V41). 칼럼의 뜻은 V41 마이그레이션에 있다. */
@Table("orders")
data class OrderRowEntity(
    @Id val id: Long? = null,
    val userId: Long,
    val exchange: String,
    val market: String,
    val side: String,
    val clientOrderId: String? = null,
    val exchangeOrderId: String? = null,
    val status: String,
    val exchangeState: String? = null,
    val requestedFunds: Double? = null,
    val requestedVolume: Double? = null,
    val decisionPrice: Double? = null,
    val filledVolume: Double? = null,
    val filledFunds: Double? = null,
    val avgFillPrice: Double? = null,
    val fee: Double? = null,
    val feeBasis: String? = null,
    val positionVolumeAfter: Double? = null,
    val bookAvgPrice: Double? = null,
    val pnlPercent: Double? = null,
    val pnlAmount: Double? = null,
    val reason: String? = null,
    val strategy: String? = null,
    val fillSource: String? = null,
    val note: String? = null,
    val tradeExecutionId: Long? = null,
    val tradeRecordId: Long? = null,
    val createdAt: Instant? = null,
    val updatedAt: Instant? = null,
    val completedAt: Instant? = null,
)
