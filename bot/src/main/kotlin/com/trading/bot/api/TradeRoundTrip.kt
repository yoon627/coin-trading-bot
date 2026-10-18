package com.trading.bot.api

import com.trading.bot.persistence.entity.TradeRecordEntity
import java.time.Duration
import java.time.LocalDateTime

/** 매수→매도 한 쌍. `trade_records` 에는 BUY/SELL 을 잇는 키가 없어 조회 시점에 조립한다. */
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
    /** 매도 시점에 기록된 수익률(수수료 차감)의 수량가중 평균. */
    val pnlPercent: Double?,
    /**
     * 매도 행 `pnl_amount` 의 합. 매도 하나라도 값이 없으면 null 이다.
     * 기록 시점 평단으로 계산된 값이라 [partial] 이어도 내지만, 포지션의 정확한 손익이 아니라 기록 기준 합이다.
     */
    val pnlAmountNet: Double?,
    /** 매도총액 − 판 수량의 매수 원가. 수수료 미차감이라 [pnlPercent] 와 부호가 어긋날 수 있다. */
    val pnlAmountGross: Double?,
    val holdingSeconds: Long?,
    val reason: String?,
    val strategy: String?,
    /** 전량 청산되지 않았고 잔량을 다음 그룹에 넘기지도 않았다. */
    val open: Boolean,
    /** 매도는 있었지만 아직 잔량을 보유 중이다. */
    val partiallyClosed: Boolean,
    /** 매수 기록이 없거나 일부만 남아 평단·손익금액을 믿을 수 없다. */
    val partial: Boolean,
    /** 다음 그룹의 엔진 매수가 넘겨받은 잔량. 엔진 매수 기록은 그 시점 전체 보유량이라 이 잔량을 이미 담고 있다. */
    val carriedVolume: Double?,
)

/** 코인 수량은 소수라 잔량 0 을 정확히 비교할 수 없다. 이 이하면 청산된 것으로 본다. */
private const val VOLUME_EPSILON = 1e-8

/** 과거 수동 주문 행의 `strategy` 값. BUY 행의 스냅샷/증분 판정에만 쓴다. */
private const val MANUAL_STRATEGY = "manual"

/**
 * 과거 수동 매수 행의 수량은 `주문금액 / 조회시점가격` 추정이라 실제 매도량과 어느 방향으로든 어긋난다.
 * 그 오차를 흡수하는 대칭 비율이다. 넓히면 이전 포지션의 실제 잔여분까지 청산으로 삼켜 없는 손익을 만든다.
 */
private const val ESTIMATE_TOLERANCE_RATIO = 0.0025

/**
 * `strategy` 가 없는 행은 출처를 알 수 없어 증분으로 본다.
 * 엔진이 `strategy` 없이 매수를 기록하면 증분으로 합산돼 매수량이 부풀려진다.
 */
private fun TradeRecordEntity.isSnapshotBuy(): Boolean =
    strategy != null && !strategy.equals(MANUAL_STRATEGY, ignoreCase = true)

/**
 * 한 포지션의 매수 수량·금액. 엔진 매수 기록은 그 시점 거래소 전체 잔고의 스냅샷이고 과거 수동 매수 행은 그 주문분만 담아,
 * 마지막 엔진 스냅샷에 그 뒤의 수동 증분을 더한다. 스냅샷이 어느 증분을 포함하는지는 `created_at` 순서로 추론한 추정이다.
 */
private data class BuySide(
    val volume: Double,
    val amount: Double,
    val count: Int,
    /** 추정으로 얻은 수량(수동 증분). 스냅샷과 매도는 거래소 실측이라 오차는 여기서만 생긴다. */
    val estimatedVolume: Double,
) {
    /**
     * 잔량 0 판정의 허용 오차. 포지션 전체에 비례시키면 작은 수동 증분이 통째로 삼켜져
     * 보유 중인 포지션이 청산으로 보이므로 추정 수량에만 비례시킨다.
     */
    val closureTolerance: Double
        get() = maxOf(VOLUME_EPSILON, estimatedVolume * ESTIMATE_TOLERANCE_RATIO)

    companion object {
        fun of(buys: List<TradeRecordEntity>): BuySide {
            val lastSnapshot = buys.indexOfLast { it.isSnapshotBuy() }
            val snapshot = buys.getOrNull(lastSnapshot)
            // 시세 조회 실패로 수량 0·금액만 남은 과거 행은 빼야 평단이 부풀지 않는다.
            val increments = buys.drop(lastSnapshot + 1).filter { it.volume > 0.0 }
            val incrementVolume = increments.sumOf { it.volume }
            return BuySide(
                volume = (snapshot?.volume ?: 0.0) + incrementVolume,
                amount = (snapshot?.totalAmount ?: 0.0) + increments.sumOf { it.totalAmount },
                count = buys.size,
                estimatedVolume = incrementVolume,
            )
        }
    }
}

/**
 * 한 포지션은 잔량이 0 이 될 때까지의 매수·매도 묶음이다. 매도가 여러 건으로 나뉠 수 있어 SELL 하나를 종료로 보지 않는다.
 * [records] 는 시간 오름차순이어야 한다. [truncatedHead] 면 각 티커의 첫 그룹은 매수가 잘렸을 수 있어 `partial` 로 표시한다.
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

        fun flush(closed: Boolean, carriedVolume: Double? = null) {
            result.add(roundTrip(ticker, buys, sells, closed, headCut = truncatedHead && isFirstGroup, carriedVolume))
            isFirstGroup = false
            buys = mutableListOf()
            sells = mutableListOf()
        }

        fun isClosed(): Boolean {
            val side = BuySide.of(buys)
            return side.volume - sells.sumOf { it.volume } <= side.closureTolerance
        }

        /**
         * 이 매수가 넘겨받는 현재 그룹의 잔량. 엔진 매수는 그 시점 전체 보유량이라 넘겨받지 않으면 같은 코인이 두 그룹에 잡힌다.
         * 스냅샷이 잔량보다 작으면 잔량이 거래소 밖으로 빠진 것이라 넘기지 않는다.
         */
        fun carriedBy(buy: TradeRecordEntity): Double? {
            if (!buy.isSnapshotBuy()) return null
            val side = BuySide.of(buys)
            val remaining = side.volume - sells.sumOf { it.volume }
            return remaining.takeIf { it > side.closureTolerance && buy.volume >= it - side.closureTolerance }
        }

        for (row in rows) {
            if (row.side.equals("BUY", ignoreCase = true)) {
                // 매도가 시작된 뒤의 매수는 새 포지션이다. 여기서 끊지 않으면 보유기간과 손익이 섞인다.
                if (sells.isNotEmpty()) {
                    val carried = carriedBy(row)
                    flush(closed = carried != null || isClosed(), carriedVolume = carried)
                }
                buys.add(row)
            } else {
                sells.add(row)
                if (isClosed()) flush(closed = true)
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
    carriedVolume: Double?,
): TradeRoundTrip {
    val buySide = BuySide.of(buys)
    val buyAmount = buySide.amount
    val buyVolume = buySide.volume
    val sellAmount = sells.sumOf { it.totalAmount }
    val sellVolume = sells.sumOf { it.volume }
    val entryAt = buys.firstOrNull()?.createdAt
    val entryPrice = if (buyVolume > 0) buyAmount / buyVolume else null
    // 잔량이 남았다고 매도 정보까지 비우면 이미 실현된 매도가 화면에서 사라진다.
    val hasSells = sells.isNotEmpty()
    val exitAt = if (hasSells) sells.lastOrNull()?.createdAt else null
    // 매수보다 많이 팔렸으면 원가를 모르는 이전 잔여분까지 판 것이라 손익을 비운다.
    // 추정 수량 오차는 청산 판정과 같은 허용 오차로 흡수해야 두 판정이 서로 어긋나지 않는다.
    val oversold = buys.isNotEmpty() && sellVolume > buyVolume + buySide.closureTolerance
    val untrustedBuys = buys.isEmpty() || headCut || oversold

    return TradeRoundTrip(
        ticker = ticker,
        entryAt = entryAt,
        entryPrice = entryPrice,
        buyCount = buys.size,
        buyAmount = buyAmount,
        buyVolume = buyVolume,
        sellCount = sells.size,
        exitAt = exitAt,
        exitPrice = if (hasSells && sellVolume > 0) sellAmount / sellVolume else null,
        sellAmount = if (hasSells) sellAmount else null,
        sellVolume = if (hasSells) sellVolume else null,
        pnlPercent = if (hasSells) weightedPnlPercent(sells) else null,
        pnlAmountNet = if (hasSells) netPnlAmount(sells) else null,
        // 판 만큼의 원가만 뺀다. 매수액을 모를 때 0 으로 채우면 매도액 전체가 이익으로 보인다.
        pnlAmountGross = if (hasSells && !untrustedBuys && entryPrice != null) {
            sellAmount - entryPrice * sellVolume
        } else {
            null
        },
        holdingSeconds = if (exitAt != null && entryAt != null) {
            Duration.between(entryAt, exitAt).seconds
        } else {
            null
        },
        reason = sells.lastOrNull()?.reason,
        // 수동 매수 위에 엔진이 매수한 포지션은 엔진 전략으로 귀속한다. 그래야 같은 포지션의 SELL 기록과 맞는다.
        strategy = buys.firstOrNull { it.strategy != null && !it.strategy.equals(MANUAL_STRATEGY, ignoreCase = true) }
            ?.strategy
            ?: buys.firstOrNull()?.strategy,
        // 잔량을 다음 그룹에 넘긴 경우는 closed 로 들어온다.
        open = !closed,
        partiallyClosed = hasSells && !closed,
        partial = untrustedBuys,
        carriedVolume = carriedVolume,
    )
}

/** 부분합은 전체 손익이 아니므로 하나라도 값이 없으면 null 이다. */
private fun netPnlAmount(sells: List<TradeRecordEntity>): Double? =
    if (sells.all { it.pnlAmount != null }) sells.sumOf { it.pnlAmount!! } else null

private fun weightedPnlPercent(sells: List<TradeRecordEntity>): Double? {
    val known = sells.filter { it.pnlPercent != null && it.volume > 0 }
    if (known.isEmpty()) return null
    val volume = known.sumOf { it.volume }
    return if (volume > 0) known.sumOf { it.pnlPercent!! * it.volume } / volume else null
}
