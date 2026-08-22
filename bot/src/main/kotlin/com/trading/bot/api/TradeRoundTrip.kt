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
    /** 분할 매도 횟수. 청산 전이어도 부분 매도가 있었으면 0 보다 크다. */
    val sellCount: Int,
    /** 마지막 매도 시각. 청산 전이면 null. */
    val exitAt: LocalDateTime?,
    /** 매도 금액가중 평균가. 청산 전이면 null. */
    val exitPrice: Double?,
    val sellAmount: Double?,
    val sellVolume: Double?,
    /** 매도 시점 기록된 수익률의 수량가중 평균. 왕복 수수료가 차감된 net. */
    val pnlPercent: Double?,
    /** 매도총액 − 매수총액. 수수료 미차감 gross 라 [pnlPercent] 와 부호가 어긋날 수 있다. */
    val pnlAmountGross: Double?,
    val holdingSeconds: Long?,
    val reason: String?,
    val strategy: String?,
    /** 아직 전량 청산되지 않았다(미매수 잔량 보유). */
    val open: Boolean,
    /** 매수 기록이 없거나 일부만 남아 매수 기반 값(평단·손익금액)을 믿을 수 없다. */
    val partial: Boolean,
)

/** 코인 수량은 소수라 잔량 0 을 정확히 비교할 수 없다. 이 이하면 청산된 것으로 본다. */
private const val VOLUME_EPSILON = 1e-8

/**
 * 한 포지션은 "매수 누적 → 매도 누적으로 잔량이 0 이 될 때까지" 이다.
 *
 * 엔진 청산(`TradingState.markSold`)은 항상 전량이지만 **수동 매도(`ManualTradeController`)는 수량 지정이
 * 가능**해 한 포지션이 여러 SELL 로 나뉠 수 있다. 그래서 SELL 하나를 곧 포지션 종료로 보지 않고
 * 수량 잔량으로 판정한다.
 *
 * @param records **시간 오름차순**으로 정렬된 레코드. 정렬이 어긋나면 그룹 경계가 틀어진다.
 * @param truncatedHead 앞쪽(오래된) 레코드가 잘려 들어왔다. 각 티커의 가장 오래된 그룹은 매수가
 *   누락됐을 수 있으므로 `partial` 로 표시한다 — 일부 매수만으로 계산된 평단을 정상처럼 보이게 두지 않는다.
 */
fun assembleRoundTrips(
    records: List<TradeRecordEntity>,
    truncatedHead: Boolean = false,
): List<TradeRoundTrip> {
    val result = mutableListOf<TradeRoundTrip>()

    records.groupBy { it.ticker }.forEach { (ticker, rows) ->
        var buys = mutableListOf<TradeRecordEntity>()
        var sells = mutableListOf<TradeRecordEntity>()
        var isFirstGroup = true

        fun flush(closed: Boolean) {
            result.add(roundTrip(ticker, buys, sells, closed, headCut = truncatedHead && isFirstGroup))
            isFirstGroup = false
            buys = mutableListOf()
            sells = mutableListOf()
        }

        for (row in rows) {
            if (row.side.equals("BUY", ignoreCase = true)) {
                buys.add(row)
            } else {
                sells.add(row)
                val remaining = buys.sumOf { it.volume } - sells.sumOf { it.volume }
                if (remaining <= VOLUME_EPSILON) flush(closed = true)
            }
        }
        if (buys.isNotEmpty() || sells.isNotEmpty()) flush(closed = false)
    }

    return result.sortedByDescending { it.exitAt ?: it.entryAt ?: LocalDateTime.MIN }
}

private fun roundTrip(
    ticker: String,
    buys: List<TradeRecordEntity>,
    sells: List<TradeRecordEntity>,
    closed: Boolean,
    headCut: Boolean,
): TradeRoundTrip {
    val buyAmount = buys.sumOf { it.totalAmount }
    val buyVolume = buys.sumOf { it.volume }
    val sellAmount = sells.sumOf { it.totalAmount }
    val sellVolume = sells.sumOf { it.volume }
    val entryAt = buys.firstOrNull()?.createdAt
    val exitAt = if (closed) sells.lastOrNull()?.createdAt else null
    // 매수 기록이 없거나(고아 SELL) 앞이 잘려 일부만 남았으면 매수 기반 값을 믿을 수 없다.
    val untrustedBuys = buys.isEmpty() || headCut

    return TradeRoundTrip(
        ticker = ticker,
        entryAt = entryAt,
        entryPrice = if (buyVolume > 0) buyAmount / buyVolume else null,
        buyCount = buys.size,
        buyAmount = buyAmount,
        buyVolume = buyVolume,
        sellCount = sells.size,
        exitAt = exitAt,
        exitPrice = if (closed && sellVolume > 0) sellAmount / sellVolume else null,
        sellAmount = if (closed) sellAmount else null,
        sellVolume = if (closed) sellVolume else null,
        pnlPercent = if (closed) weightedPnlPercent(sells) else null,
        // 매수액을 모르면 0 으로 채우지 않는다 — 매도액 전체가 이익인 것처럼 보이는 가짜 손익이 된다.
        pnlAmountGross = if (closed && !untrustedBuys) sellAmount - buyAmount else null,
        holdingSeconds = if (exitAt != null && entryAt != null) {
            Duration.between(entryAt, exitAt).seconds
        } else {
            null
        },
        reason = sells.lastOrNull()?.reason,
        strategy = buys.firstOrNull()?.strategy,
        open = !closed,
        partial = untrustedBuys,
    )
}

/** 분할 매도면 각 매도의 수익률을 수량으로 가중평균한다. 단일 매도면 그 값 그대로다. */
private fun weightedPnlPercent(sells: List<TradeRecordEntity>): Double? {
    val known = sells.filter { it.pnlPercent != null && it.volume > 0 }
    if (known.isEmpty()) return null
    val volume = known.sumOf { it.volume }
    return if (volume > 0) known.sumOf { it.pnlPercent!! * it.volume } / volume else null
}
