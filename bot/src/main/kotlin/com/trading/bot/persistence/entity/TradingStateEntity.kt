package com.trading.bot.persistence.entity

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.LocalDate
import java.time.Instant

/**
 * per-(userId, ticker) 거래 상태의 durable 스냅샷. position/avgBuyPrice/holdVolume 은 저장하지 않고
 * 재시작 시 syncPosition 이 거래소 잔고에서 복원한다(#20 설계). exit_params_json 은 진입 시점 청산 파라미터 스냅샷.
 * V23 의 rungs_filled·last_action_price·flat_peak·pending_buy_trigger_price 는 매핑하지 않는다(지운 적립 프로파일의 장부) —
 * insert 는 DB 기본값을 쓰고 update 는 건드리지 않는다.
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
    val updatedAt: Instant = Instant.now(),
)
