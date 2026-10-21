package com.trading.bot.persistence.entity

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.LocalDate
import java.time.Instant

/**
 * (userId, exchange, ticker) 별 거래 상태. 보유 수량과 평균 단가는 저장하지 않고 재시작 때 거래소 잔고에서 맞춘다.
 * exitParamsJson 은 진입 시점의 청산 파라미터다.
 */
@Table("trading_states")
data class TradingStateEntity(
    @Id val id: Long? = null,
    val userId: Long,
    val ticker: String,
    val pendingBuyUuid: String? = null,
    val pendingBuyStrategy: String? = null,
    val pendingSellUuid: String? = null,
    val pendingSellReason: String? = null,
    val pendingSellVolume: Double? = null,
    val pendingSellAvgPrice: Double? = null,
    val entryStrategy: String? = null,
    val buyDate: LocalDate? = null,
    val boughtToday: Boolean = false,
    val boughtDate: LocalDate? = null,
    val peakPrice: Double = 0.0,
    val exitParamsJson: String? = null,
    val halted: Boolean = false,
    val haltReason: String? = null,
    val reconcileFailureCount: Int = 0,
    val pendingSellSince: java.time.Instant? = null,
    val pendingSellAlerted: Boolean = false,
    val pendingBuyPriorVolume: Double? = null,
    val pendingSellTriggerPrice: Double? = null,
    val pendingSellPriorVolume: Double? = null,
    val pendingBuyIdentifier: String? = null,
    val pendingSellIdentifier: String? = null,
    // [com.trading.common.domain.Exchange.name]
    val exchange: String = "UPBIT",
    val reviewReason: String? = null,
    val updatedAt: Instant = Instant.now(),
)
