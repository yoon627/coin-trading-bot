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
    /** 매도는 있었지만 매수 수량의 일부가 아직 남아 있다. */
    val partiallyClosed: Boolean,
    /** 매수 기록이 없거나 일부만 남아 매수 기반 값(평단·손익금액)을 믿을 수 없다. */
    val partial: Boolean,
)

/** 코인 수량은 소수라 잔량 0 을 정확히 비교할 수 없다. 이 이하면 청산된 것으로 본다. */
private const val VOLUME_EPSILON = 1e-8

/** 수동 주문의 `strategy` 값 — `ManualTradeController` 가 넣는다. */
private const val MANUAL_STRATEGY = "manual"

/**
 * 한 포지션의 매수 수량·금액. 매수 기록의 의미가 경로마다 달라서 그냥 합산할 수 없다.
 *
 * `PositionManager.completeBuy` 는 거래소 **전체 잔고와 평단**을 그대로 적는다(#20 — 재시작 시
 * `syncPosition` 이 복원한 분과 이중계상되지 않게 하려는 의도다). 그래서 엔진 매수 기록은 증분 체결이
 * 아니라 **그 시점 포지션 전체의 스냅샷**이고, 여러 건을 더하면 앞선 매수가 중복으로 들어간다.
 * 반면 수동 매수(`TradeExecutionService.executeBuy`)는 그 주문의 금액·수량만 적으므로 합산이 맞다.
 *
 * 그래서 엔진 기록이 하나라도 있으면 **가장 마지막 것**이 포지션 전체를 대표하고, 수동뿐이면 합산한다.
 *
 * `strategy` 가 비어 있는 기록은 출처를 알 수 없으므로 합산하는 쪽(기존 동작)을 유지한다. 정상 흐름에서는
 * 엔진도 수동도 값을 채우므로 실데이터에는 없다.
 */
private data class BuySide(val volume: Double, val amount: Double, val count: Int) {
    companion object {
        fun of(buys: List<TradeRecordEntity>): BuySide {
            val engineSnapshot = buys.lastOrNull {
                it.strategy != null && !it.strategy.equals(MANUAL_STRATEGY, ignoreCase = true)
            }
            return if (engineSnapshot != null) {
                BuySide(engineSnapshot.volume, engineSnapshot.totalAmount, buys.size)
            } else {
                BuySide(buys.sumOf { it.volume }, buys.sumOf { it.totalAmount }, buys.size)
            }
        }
    }
}

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

        fun remaining() = BuySide.of(buys).volume - sells.sumOf { it.volume }

        for (row in rows) {
            if (row.side.equals("BUY", ignoreCase = true)) {
                // 매도가 시작된 뒤에 들어온 매수는 새 포지션이다. 잔량이 남아 있어도 여기서 끊지 않으면
                // BUY→부분SELL→BUY→SELL 이 한 행으로 뭉쳐 보유기간과 손익이 뒤섞인다.
                if (sells.isNotEmpty()) flush(closed = remaining() <= VOLUME_EPSILON)
                buys.add(row)
            } else {
                sells.add(row)
                if (remaining() <= VOLUME_EPSILON) flush(closed = true)
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
    // 이 그룹의 매수보다 많이 팔렸다면 이전 포지션에서 넘어온 잔여분까지 팔린 것이다(수동 sellAll 은
    // 거래소 잔고 전체를 판다). 그 잔여분의 원가는 이 그룹에 없어 알 수 없다.
    // 기록된 매수보다 많이 팔렸으면 그 초과분의 원가를 알 수 없다 — 이전 포지션 잔여분일 수도, 외부
    // 입금분일 수도 있다. 비율 여유를 두면 그만큼의 정체 모를 수량이 손익에 섞이므로, 흡수하는 것은
    // 부동소수 반올림뿐이다.
    //
    // 부작용: 수동 매수는 `주문금액 / 조회시점 가격` 으로 **추정**한 수량을 남기는데(#105) `sellAll` 은
    // 실제 잔고를 판다. 그래서 이전 포지션이 없는 정상 매매도 초과로 잡혀 손익이 비는 경우가 생긴다.
    // 틀린 손익을 보여주느니 비우는 편을 택했다 — 근본 해결은 #105 에서 실제 체결 수량을 기록하는 것이다.
    val oversold = buys.isNotEmpty() && sellVolume > buyVolume + VOLUME_EPSILON
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
        exitPrice = if (hasSells && sellVolume > 0) sellAmount / sellVolume else null,
        sellAmount = if (hasSells) sellAmount else null,
        sellVolume = if (hasSells) sellVolume else null,
        pnlPercent = if (hasSells) weightedPnlPercent(sells) else null,
        // 판 만큼의 원가만 차감한다 — 전체 매수액을 빼면 아직 팔지 않은 매수분이 손실로 잡힌다.
        // 매수액을 모르면 0 으로 채우지 않는다: 매도액 전체가 이익인 것처럼 보이는 가짜 손익이 된다.
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
        // 수동 매수 위에 엔진이 매수하면 런타임 entryStrategy 는 엔진 전략이 된다(수동 매수는 TradingState 를
        // 건드리지 않는다). 첫 BUY 를 그대로 쓰면 'manual' 이 나와서, 같은 포지션의 SELL 기록·V21 백필과
        // 전략 귀속이 갈린다. 엔진 기록이 있으면 그 중 **첫 번째**를 쓴다 — 여럿이면 먼저 찍힌 쪽이 남는다.
        strategy = buys.firstOrNull { it.strategy != null && !it.strategy.equals(MANUAL_STRATEGY, ignoreCase = true) }
            ?.strategy
            ?: buys.firstOrNull()?.strategy,
        // open 은 "아직 전량 청산되지 않았다" 는 뜻을 그대로 유지한다 — 일부만 팔았어도 잔량은 보유 중이다.
        open = !closed,
        partiallyClosed = hasSells && !closed,
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
