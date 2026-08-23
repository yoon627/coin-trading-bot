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
    /** 진입 평단 — 마지막 매수 기록의 가격. 엔진 매수는 거래소가 계산한 평단을 그대로 남긴다. */
    val entryPrice: Double?,
    /** 매수 체결 횟수. 수량은 누적이 아니라 스냅샷이라 [buyVolume] 과 비례하지 않는다. */
    val buyCount: Int,
    /** 마지막 매수 시점의 보유 금액(평단 × 보유수량). 매수 건별 금액의 합이 아니다. */
    val buyAmount: Double,
    /** 마지막 매수 시점의 보유 수량. */
    val buyVolume: Double,
    /** 분할 매도 횟수. */
    val sellCount: Int,
    /** 마지막 매도 시각. 매도가 없으면 null. */
    val exitAt: LocalDateTime?,
    /** 매도 금액가중 평균가. */
    val exitPrice: Double?,
    val sellAmount: Double?,
    val sellVolume: Double?,
    /** 매도 시점 기록된 수익률의 수량가중 평균. 왕복 수수료가 차감된 net. */
    val pnlPercent: Double?,
    /**
     * `매도액 − 평단 × 매도수량`. **판 만큼의 원가만** 차감하므로 일부만 팔았어도 왜곡되지 않는다.
     * 수수료 미차감 gross 라 [pnlPercent] 와 부호가 어긋날 수 있다.
     */
    val pnlAmountGross: Double?,
    val holdingSeconds: Long?,
    val reason: String?,
    val strategy: String?,
    /** 매도가 한 건도 없다. */
    val open: Boolean,
    /** 매도는 있었지만 매수 수량의 일부가 아직 남아 있다. */
    val partiallyClosed: Boolean,
    /** 매수 기록이 없거나 일부만 남아 매수 기반 값(평단·손익금액)을 믿을 수 없다. */
    val partial: Boolean,
)

/**
 * 코인 수량은 수수료 때문에 정확히 0 이 되지 않는다. 매수량의 이 비율 이하로 남은 잔량은 dust 로 보고
 * 전량 청산으로 취급한다.
 */
private const val DUST_RATIO = 0.01

/** 수동 주문이 남기는 전략명(`ManualTradeController`). 이 값이면 매수 수량은 스냅샷이 아니라 증분이다. */
private const val MANUAL_STRATEGY = "manual"

/**
 * **매도 뒤에 매수가 오면 새 포지션이다.** 연속된 매도는 같은 포지션의 분할 매도로 본다.
 *
 * 수량 잔량으로 경계를 잡던 이전 규칙은 매수 기록을 증분으로 오해해(실제로는 스냅샷) 잔량이 0 에
 * 도달하지 못했고, 그 티커의 매매가 전부 한 줄로 뭉쳤다. 그 앞의 "매도 하나 = 포지션 종료" 규칙은
 * 반대로 수동 분할 매도를 쪼개 손익을 틀리게 냈다. 매매 **순서**로 경계를 정하면 수량 해석에
 * 의존하지 않으므로 두 경우 모두 옳게 갈린다.
 *
 * 한계: 같은 포지션 중간에 추가 매수가 끼면(`BUY → 일부 SELL → BUY → SELL`) 둘로 갈린다. 수량만으로는
 * 추가 매수인지 새 진입인지 구분할 수 없어, 잘못 합치기보다 잘게 나누는 쪽을 택했다 — 나뉜 각 행의
 * 매수·매도·손익은 그 자체로는 정확하다.
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

        fun flush() {
            result.add(roundTrip(ticker, buys, sells, headCut = truncatedHead && isFirstGroup))
            isFirstGroup = false
            buys = mutableListOf()
            sells = mutableListOf()
        }

        for (row in rows) {
            val isBuy = row.side.equals("BUY", ignoreCase = true)
            if (isBuy && sells.isNotEmpty()) flush()
            if (isBuy) buys.add(row) else sells.add(row)
        }
        if (buys.isNotEmpty() || sells.isNotEmpty()) flush()
    }

    return result.sortedByDescending { it.exitAt ?: it.entryAt ?: LocalDateTime.MIN }
}

private fun roundTrip(
    ticker: String,
    buys: List<TradeRecordEntity>,
    sells: List<TradeRecordEntity>,
    headCut: Boolean,
): TradeRoundTrip {
    // 매수 기록에는 두 종류가 섞여 있다:
    //  - 엔진 매수(`PositionManager.completeBuy`)는 거래소 **실잔고와 평단**을 남기는 그 시점 **스냅샷**
    //  - 수동 매수(`TradeExecutionService.executeBuy`)는 이번 주문분만 남기는 **증분** (strategy="manual")
    // 따라서 마지막 스냅샷 이후의 증분을 더해야 그 시점 총 보유량이 된다. 전부 합산하면 같은 보유분을
    // 여러 번 세고, 마지막 행만 쓰면 뒤따르는 수동 매수분이 누락된다.
    val lastSnapshot = buys.indexOfLast { !it.strategy.equals(MANUAL_STRATEGY, ignoreCase = true) }
    val heldBuys = if (lastSnapshot >= 0) buys.subList(lastSnapshot, buys.size) else buys
    val buyAmount = heldBuys.sumOf { it.totalAmount }
    val buyVolume = heldBuys.sumOf { it.volume }
    val entryPrice = if (buyVolume > 0) buyAmount / buyVolume else null
    val sellAmount = sells.sumOf { it.totalAmount }
    val sellVolume = sells.sumOf { it.volume }
    val entryAt = buys.firstOrNull()?.createdAt
    val exitAt = sells.lastOrNull()?.createdAt
    // 이 그룹의 매수보다 많이 팔렸다면 이전 포지션에서 넘어온 잔여분까지 팔린 것이다
    // (수동 sellAll 은 거래소 잔고 전체를 판다). 그 잔여분의 원가는 이 그룹에 없어 알 수 없다.
    val oversold = buys.isNotEmpty() && sellVolume > buyVolume * (1 + DUST_RATIO)
    // 매수 기록이 없거나(고아 SELL) 앞이 잘렸거나 초과 매도면 매수 기반 값을 믿을 수 없다.
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
        exitPrice = if (sellVolume > 0) sellAmount / sellVolume else null,
        sellAmount = sellAmount.takeIf { sells.isNotEmpty() },
        sellVolume = sellVolume.takeIf { sells.isNotEmpty() },
        pnlPercent = weightedPnlPercent(sells),
        // 판 만큼의 원가만 차감한다 — 전체 매수액을 빼면 아직 팔지 않은 매수분이 손실로 잡힌다.
        pnlAmountGross = if (sells.isNotEmpty() && !untrustedBuys && entryPrice != null) {
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
        strategy = buys.firstOrNull()?.strategy,
        open = sells.isEmpty(),
        partiallyClosed = sells.isNotEmpty() && buyVolume - sellVolume > buyVolume * DUST_RATIO,
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
