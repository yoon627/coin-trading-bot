package com.trading.bot.api

import com.trading.bot.persistence.entity.TradeRecordEntity
import java.time.Duration
import java.time.LocalDateTime

/**
 * 매수→매도 한 쌍. `trade_records` 는 BUY/SELL 을 독립 행으로 쌓고 둘을 잇는 키가 없어 조회 시점에 조립한다.
 */
data class TradeRoundTrip(
    val ticker: String,
    val entryAt: LocalDateTime?,
    /** 매수 금액가중 평단. */
    val entryPrice: Double?,
    val buyCount: Int,
    val buyAmount: Double,
    val buyVolume: Double,
    val exitAt: LocalDateTime?,
    val exitPrice: Double?,
    val sellAmount: Double?,
    val sellVolume: Double?,
    /** 매도 시점 기록된 수익률. 왕복 수수료가 차감된 net. */
    val pnlPercent: Double?,
    /** 매도총액 − 매수총액. 수수료 미차감 gross 라 [pnlPercent] 와 부호가 어긋날 수 있다. */
    val pnlAmountGross: Double?,
    val holdingSeconds: Long?,
    val reason: String?,
    val strategy: String?,
    /** 아직 청산되지 않은 보유분. */
    val open: Boolean,
    /** 선행 매수 기록이 없어 매수 정보가 비었다(조회 상한으로 잘렸거나 유실). */
    val partial: Boolean,
)

/**
 * 매도는 항상 전량 청산(`TradingState.markSold` 가 holdVolume 을 0 으로)이므로
 * "직전 SELL 이후의 BUY 들 + 그 SELL" 이 한 포지션으로 결정적으로 묶인다. 부분 매도가 없어 모호성이 없다.
 *
 * @param records **시간 오름차순**으로 정렬된 레코드. 정렬이 어긋나면 그룹 경계가 틀어진다.
 */
fun assembleRoundTrips(records: List<TradeRecordEntity>): List<TradeRoundTrip> {
    val result = mutableListOf<TradeRoundTrip>()

    records.groupBy { it.ticker }.forEach { (ticker, rows) ->
        var buys = mutableListOf<TradeRecordEntity>()
        for (row in rows) {
            if (row.side.equals("BUY", ignoreCase = true)) {
                buys.add(row)
            } else {
                result.add(roundTrip(ticker, buys, row))
                buys = mutableListOf()
            }
        }
        if (buys.isNotEmpty()) result.add(roundTrip(ticker, buys, sell = null))
    }

    return result.sortedByDescending { it.exitAt ?: it.entryAt ?: LocalDateTime.MIN }
}

private fun roundTrip(
    ticker: String,
    buys: List<TradeRecordEntity>,
    sell: TradeRecordEntity?,
): TradeRoundTrip {
    val buyAmount = buys.sumOf { it.totalAmount }
    val buyVolume = buys.sumOf { it.volume }
    val entryAt = buys.firstOrNull()?.createdAt
    return TradeRoundTrip(
        ticker = ticker,
        entryAt = entryAt,
        entryPrice = if (buyVolume > 0) buyAmount / buyVolume else null,
        buyCount = buys.size,
        buyAmount = buyAmount,
        buyVolume = buyVolume,
        exitAt = sell?.createdAt,
        exitPrice = sell?.price,
        sellAmount = sell?.totalAmount,
        sellVolume = sell?.volume,
        pnlPercent = sell?.pnlPercent,
        // 매수 기록이 없으면 0 으로 채우지 않는다 — 매도총액 전체가 이익인 것처럼 보이는 가짜 손익이 된다.
        pnlAmountGross = if (sell != null && buys.isNotEmpty()) sell.totalAmount - buyAmount else null,
        holdingSeconds = if (sell != null && entryAt != null) {
            Duration.between(entryAt, sell.createdAt).seconds
        } else {
            null
        },
        reason = sell?.reason,
        strategy = buys.firstOrNull()?.strategy,
        open = sell == null,
        partial = sell != null && buys.isEmpty(),
    )
}
