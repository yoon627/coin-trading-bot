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
 * 수동 매수 수량은 `주문금액 / 조회시점가격` 추정이라(`TradeExecutionService.executeBuy`) 실측 매도와
 * 정확히 상쇄되지 않는다. 오차 **부호는 정해져 있지 않다** — 기록 수량이 실제보다 큰지 작은지는
 * `체결가 / 조회시점가격` 이 `1 - 수수료율(≈0.0005)` 보다 큰지에 달렸고, 체결과 틱 조회 사이의 가격
 * 변동이 그 폭을 양방향으로 넘나든다. 그래서 단방향이 아니라 대칭 비율로 흡수한다.
 *
 * 폭은 결정적 성분(수수료 0.05%)에 드리프트 여유를 더한 만큼으로 좁게 잡는다. 넓힐수록 진짜 잔여분
 * (이전 포지션에서 넘어온 수량)까지 삼켜 없는 손익을 만들어낸다.
 *
 * **상한은 계약에 눌려 있다** — `추정 매수라도 기록보다 많이 팔렸으면 손익을 비운다` 가 초과 0.3% 를
 * 실제 잔여분으로 본다. 다만 그 0.3% 는 허용 오차가 `1e-8` 이던 시절 임의로 고른 수치이고 "0.29% 는
 * 추정 오차"라고 주장한 적이 없다. 도메인 사실이 아니라 **현재의 제약**으로 읽어야 한다.
 *
 * 이 비율 이내의 잔량은 청산으로 본다. 대가로 실제 잔여분이 이 비율 미만이면 청산으로 표시되고,
 * 나중에 그 dust 를 실제로 팔면 매수 없는 고아 SELL 행이 목록에 하나 더 생긴다.
 * 근본 해결은 수동 주문도 실제 체결 수량을 기록하는 것이다(이슈 #105).
 */
private const val ESTIMATE_TOLERANCE_RATIO = 0.0025

/**
 * 이 행을 **스냅샷으로 분류하는가**. 사실 판정이 아니라 정책이다 — `strategy` 가 비어 있으면 출처를
 * 알 수 없어 스냅샷이 아닌 쪽(증분·합산)으로 보낸다. 엔진이 `strategy` 없이 기록하는 경로가 실재하면
 * (`PositionManager.kt` 의 pendingBuyStrategy null 허용) 그 행은 증분으로 잘못 합산된다 — 이슈로 남겼다.
 */
private fun TradeRecordEntity.isSnapshotBuy(): Boolean =
    strategy != null && !strategy.equals(MANUAL_STRATEGY, ignoreCase = true)

/**
 * 한 포지션의 매수 수량·금액. 매수 기록의 의미가 경로마다 달라서 그냥 합산할 수 없다.
 *
 * `PositionManager.completeBuy` 는 거래소 **전체 잔고와 평단**을 그대로 적는다(#20 — 재시작 시
 * `syncPosition` 이 복원한 분과 이중계상되지 않게 하려는 의도다). 그래서 엔진 매수 기록은 증분 체결이
 * 아니라 **그 시점 포지션 전체의 스냅샷**이고, 여러 건을 더하면 앞선 매수가 중복으로 들어간다.
 * 반면 수동 매수(`TradeExecutionService.executeBuy`)는 그 주문의 금액·수량만 적으므로 합산이 맞다.
 *
 * 그래서 규칙은 **마지막 엔진 스냅샷 + 그 이후의 수동 증분들** 이다. 스냅샷은 그 시점까지의 보유분을
 * 이미 담고 있으므로 앞선 수동 매수를 다시 더하면 이중계상이고, 뒤따르는 수동 매수를 빠뜨리면 누락이다.
 *
 * `strategy` 가 비어 있는 기록은 출처를 알 수 없으므로 스냅샷이 아니라 증분으로 취급한다(합산하는 쪽).
 * 정상 흐름에서는 엔진도 수동도 값을 채우므로 실데이터에는 없다.
 *
 * ⚠️ 이 값은 **불변식이 아니라 조회 시점의 최선 추정**이다. 어떤 기록도 "이 스냅샷이 저 수동 매수를
 * 포함하는가"를 직접 말해주지 않아, 순서(`created_at`)로 추론한다. 수동 주문 체결과 그 행 기록 사이에
 * 엔진 스냅샷이 끼면 어느 쪽에도 안 잡힐 수 있다(dust 수준).
 */
private data class BuySide(
    val volume: Double,
    val amount: Double,
    val count: Int,
    /**
     * 이 중 추정으로 얻은 수량(수동 증분). 오차는 **여기서만** 생긴다 — 스냅샷과 매도는 거래소 실측이다.
     * 매도 쪽 추정(`executeSellVolume` 이 요청 수량을 적는 문제, #105)은 이 판정 밖이다.
     */
    val estimatedVolume: Double,
) {
    /**
     * 잔량 0 판정에 쓸 허용 오차. **추정이 들어간 수량에만** 비례한다 — 포지션 전체에 비례시키면
     * 수동 증분이 포지션의 그 비율보다 작을 때 증분이 통째로 삼켜져, 실제로는 보유 중인 포지션이
     * 청산으로 표시된다(이 수정이 고치려던 #132 증상 그 자체).
     */
    val closureTolerance: Double
        get() = maxOf(VOLUME_EPSILON, estimatedVolume * ESTIMATE_TOLERANCE_RATIO)

    companion object {
        fun of(buys: List<TradeRecordEntity>): BuySide {
            // 스냅샷이 없으면(-1) 전부가 증분이고, 있으면 그 뒤만 증분이다.
            val lastSnapshot = buys.indexOfLast { it.isSnapshotBuy() }
            val snapshot = buys.getOrNull(lastSnapshot)
            // 수량 0 인데 금액만 있는 행은 제외한다 — `executeBuy` 가 시세 조회 실패 시 그렇게 남긴다
            // (`currentPrice=0` → `volume=0`, `totalAmount`=주문 전액). 금액만 더하면 평단이 부풀려진다.
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

        fun isClosed(): Boolean {
            val side = BuySide.of(buys)
            return side.volume - sells.sumOf { it.volume } <= side.closureTolerance
        }

        for (row in rows) {
            if (row.side.equals("BUY", ignoreCase = true)) {
                // 매도가 시작된 뒤에 들어온 매수는 새 포지션이다. 잔량이 남아 있어도 여기서 끊지 않으면
                // BUY→부분SELL→BUY→SELL 이 한 행으로 뭉쳐 보유기간과 손익이 뒤섞인다.
                if (sells.isNotEmpty()) flush(closed = isClosed())
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
    //
    // 수동 매수는 `주문금액 / 조회시점 가격` 으로 **추정**한 수량을 남기는데(#105) `sellAll` 은 실제
    // 잔고를 판다. 그래서 이전 포지션이 없는 정상 매매도 초과로 잡힐 수 있다. 추정분에 비례하는 허용
    // 오차까지는 그 오차로 보고 흡수하고, 넘으면 원가 미상으로 보아 손익을 비운다 — 틀린 손익을
    // 보여주느니 비우는 편이다. 근본 해결은 #105 에서 실제 체결 수량을 기록하는 것이다.
    //
    // 청산 판정과 같은 허용 오차를 쓴다. 기준이 다르면 "청산됐는데 매수 기록을 못 믿는다" 는 모순이 생긴다.
    val oversold = buys.isNotEmpty() && sellVolume > buyVolume + buySide.closureTolerance
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
