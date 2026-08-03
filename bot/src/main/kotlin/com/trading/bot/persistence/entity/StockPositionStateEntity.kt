package com.trading.bot.persistence.entity

import java.time.Instant
import java.time.LocalDate
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table

/**
 * per-(userId, exchange, symbol) 주식 포지션 상태의 durable 스냅샷 (#64).
 *
 * 보유수량·평단은 없다 — 재시작 시 `getHoldings()` 가 진실이다. 여기 담는 것은 거래소가 알려주지 않는 값뿐이다.
 */
@Table("stock_position_state")
data class StockPositionStateEntity(
    @Id val id: Long? = null,
    val userId: Long,
    val exchange: String = "KIS",
    val symbol: String,
    val peakPrice: Double = 0.0,
    /** 마지막 매수 접수 거래일(KST). `boughtToday` 플래그는 이 날짜와 오늘을 비교해 복원한다. */
    val boughtDate: LocalDate? = null,
    val entryStrategy: String? = null,
    val updatedAt: Instant = Instant.now(),
)
