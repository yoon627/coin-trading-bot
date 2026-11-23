package com.trading.bot.api

import com.fasterxml.jackson.annotation.JsonIgnore
import com.trading.bot.domain.FillSource
import com.trading.bot.persistence.entity.OrderRowEntity
import com.trading.bot.persistence.entity.TradeRecordEntity
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 거래 기록 화면(`/api/trades`·`/roundtrips`)의 한 줄. 필드와 순서는 지금 응답인 [TradeRecordEntity] 와 같다.
 * 엔진 매수의 가격·수량·총액은 이 주문이 아니라 체결 뒤 보유 전체다(옛 trade_records 의 뜻 그대로).
 */
data class TradeHistoryRow(
    val id: Long?,
    val ticker: String,
    val side: String,
    val price: Double,
    val volume: Double,
    val totalAmount: Double,
    val orderAmount: Double?,
    val pnlPercent: Double?,
    val pnlAmount: Double?,
    val reason: String?,
    val strategy: String?,
    val userId: Long?,
    val createdAt: LocalDateTime,
    @get:JsonIgnore val fillSource: FillSource? = null,
    /** 화면 값을 만들 칼럼이 비어 0 으로 채웠다. */
    @get:JsonIgnore val incomplete: Boolean = false,
)

fun TradeRecordEntity.toHistoryRow() = TradeHistoryRow(
    id = id, ticker = ticker, side = side, price = price, volume = volume, totalAmount = totalAmount, orderAmount = orderAmount,
    pnlPercent = pnlPercent, pnlAmount = pnlAmount, reason = reason, strategy = strategy, userId = userId, createdAt = createdAt,
)

/**
 * orders 의 체결 줄에서 trade_records 가 냈을 값을 다시 만든다. 총액은 엔진이 기록할 때와 같은 곱셈이다.
 * 시각은 체결 시각을 [zone](trade_records 를 쓴 JVM 시간대)의 LocalDateTime 으로 바꾼다.
 */
fun OrderRowEntity.toHistoryRow(zone: ZoneId): TradeHistoryRow {
    val source = FillSource.parseOrNull(fillSource)
    val manualBuy = side == "BUY" && source == FillSource.BACKFILL_MANUAL
    val price = if (side == "SELL" || manualBuy) decisionPrice else bookAvgPrice
    val volume = when {
        side == "SELL" -> filledVolume ?: requestedVolume
        manualBuy -> filledVolume
        else -> positionVolumeAfter
    }
    val total = if (manualBuy) filledFunds else if (price != null && volume != null) price * volume else null
    val at = completedAt ?: createdAt
    return TradeHistoryRow(
        id = id,
        ticker = market,
        side = side,
        price = price ?: 0.0,
        volume = volume ?: 0.0,
        totalAmount = total ?: 0.0,
        orderAmount = if (manualBuy) null else filledFunds,
        pnlPercent = pnlPercent,
        pnlAmount = pnlAmount,
        reason = reason,
        strategy = strategy,
        userId = userId,
        createdAt = LocalDateTime.ofInstant(at ?: Instant.EPOCH, zone),
        fillSource = source,
        incomplete = price == null || volume == null || total == null || completedAt == null,
    )
}
