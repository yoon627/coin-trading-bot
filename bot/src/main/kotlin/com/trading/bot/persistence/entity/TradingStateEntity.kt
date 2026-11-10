package com.trading.bot.persistence.entity

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.LocalDate
import java.time.Instant

/**
 * (userId, exchange, ticker) 별 거래 상태. 봇 장부(봇이 산 수량·평단)는 book_* 로 저장하고, 재시작 때 거래소 잔고로
 * 줄이기만 한다(V34). exitParamsJson 은 진입 시점의 청산 파라미터다.
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
    val bookVolume: Double? = null,
    val bookAvgPrice: Double? = null,
    // 장부를 쓸 때 updatedAt 과 같은 값. 다르면 장부 칼럼을 모르는 옛 버전이 행을 저장한 것이라 장부를 믿지 않는다.
    val bookWrittenAt: Instant? = null,
    val adoptBaselineVolume: Double? = null,
    // 미해소 매수를 낼 때의 봇 장부. 재시작 뒤 체결을 이 위에만 더한다.
    val pendingBuyBookVolume: Double? = null,
    val pendingBuyBookAvg: Double? = null,
    val sellClearPriorVolume: Double? = null,
    val sellClearVolume: Double? = null,
)
