package com.trading.bot.domain

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.math.max

data class TradingState(
    val ticker: String,
    var position: Boolean = false,
    var avgBuyPrice: Double = 0.0,
    var holdVolume: Double = 0.0,
    var peakPrice: Double = 0.0,
    var buyDate: LocalDate? = null,
    var boughtToday: Boolean = false,
    // boughtToday 가 가리키는 거래일. 재시작 후 그 플래그가 오늘 것인지 어제 것인지 구분하는 유일한 근거다
    // (프로세스 수명 플래그로는 구분 불가 — 같은 거래일 재시작이면 재매수가 뚫리고, 경계를 넘겼으면 매수가 막힌다).
    var boughtDate: LocalDate? = null,
    var lastTradeTime: LocalDateTime? = null,
    var entryStrategy: String? = null,
    // H8: placeOrder 성공 후 후처리(awaitFill/getAccounts) 실패 시 주문 uuid 를 보존해 다음 tick reconcile.
    var pendingBuyUuid: String? = null,
    var pendingBuyStrategy: String? = null,
    // 매도판 H8: 매도 placeOrder 성공 후 체결확인 실패/미확정 시 uuid·사유 보존 → 다음 tick reconcilePendingSell.
    // 잃으면 뒤늦게 체결된 청산이 기록에서 사라지거나(감사 유실) 재평가로 이중 매도된다.
    var pendingSellUuid: String? = null,
    var pendingSellReason: SellReason? = null,
    // 매도 주문 시점의 수량·평단. 재시작 후엔 syncPosition 이 잔고 0 을 반영해 holdVolume/avgBuyPrice 가
    // 이미 비어 있으므로, 이 값 없이 잔고만 보고 청산을 확정하면 수량 0·손익 없음의 유령 SELL 이 기록된다.
    var pendingSellVolume: Double? = null,
    var pendingSellAvgPrice: Double? = null,
    // syncPosition 이 거래소 잔고 동기화에 실패하면 true — position 상태가 불확실하므로 매수를 막아
    // 재시작 직후(429 빈발) 이중 포지션을 방지. processTicker 가 다음 tick 재시도해 성공 시 해소.
    var unsynced: Boolean = false,
    // #19: reconcilePendingBuy 의 getOrder·잔고조회가 연속 실패해 pending 이 무한 재시도되면 halt.
    // 신규 매수만 막는다(buy() 가드) — 매도·reconcile·잔고 동기화까지 막으면 포지션이 청산 못 하고 갇힌다.
    var halted: Boolean = false,
    var haltReason: String? = null,
    var reconcileFailureCount: Int = 0,
    // 매도 pending 이 시작된 시각. 미해소가 얼마나 오래 끌었는지는 재시작 횟수와 무관해야 하므로
    // 카운터가 아니라 시각을 durable 로 남긴다(#55). pending 이 없으면 null.
    var pendingSellSince: Instant? = null,
    // 위 임계를 넘겨 이미 알린 pending 인지. durable 이라 재시작해도 중복 발화하지 않는다.
    var pendingSellAlerted: Boolean = false,
    // 진입 시점 청산 파라미터 스냅샷. 저장·복원 전용 — 소비(진입 시점 값으로 청산)는 strategy-evolution Phase 2.
    var exitParams: ExitParamsSnapshot? = null,
    // durable pending 기록이 실패하면 true — 크래시 시 pending 유실 위험이 있으므로 신규 진입을 막는다(비영속, unsynced 동형).
    var pendingPersistFailed: Boolean = false,
    // 신고점 flush 가 실패하면 true — 갱신 tick 에만 flush 하므로 그대로 두면 재시도 기회가
    // 사라진다(하락 전환 시 다시 갱신될 일이 없다). 다음 tick 에서 재기록한다(비영속).
    // 매수는 막지 않는다 — 고점 유실은 청산 정확도 문제이지 주문 유실 위험이 아니다(#54).
    var peakPersistFailed: Boolean = false,
) {
    fun pnlPercent(currentPrice: Double): Double {
        if (avgBuyPrice <= 0) return 0.0
        return ((currentPrice - avgBuyPrice) / avgBuyPrice) * 100.0
    }

    fun dropFromPeakPercent(currentPrice: Double): Double {
        if (peakPrice <= 0) return 0.0
        return ((peakPrice - currentPrice) / peakPrice) * 100.0
    }

    /** @return 신고점 갱신 여부 — durable flush 를 갱신 시점에만 걸기 위한 신호(매 tick upsert 는 write 증폭). */
    fun updatePeakPrice(currentPrice: Double): Boolean {
        if (currentPrice <= peakPrice) return false
        peakPrice = currentPrice
        return true
    }

    fun resetDaily(tradingDate: LocalDate) {
        // H8: pendingBuyUuid 는 건드리지 않는다(끄면 미해소 주문이 다음날 재매수로 이어져 H8 재발).
        if (boughtDate != tradingDate) boughtToday = false
    }

    /**
     * @param replace reconcile 확정(재시작 복원)에서 거래소 실잔고를 절대값으로 반영할 때 true. syncPosition 이
     * 이미 position 을 채운 상태에서 추가매수 averaging 으로 이중계상되는 것을 막는다(#20). buyDate·entryStrategy 는
     * durable 복원값이 있으면 유지(재시작으로 진입일/진입전략이 뒤덮이지 않게).
     */
    fun markBought(price: Double, volume: Double, strategy: String? = null, replace: Boolean = false, now: LocalDateTime = LocalDateTime.now(TradingDay.KST)) {
        // 이미 포지션이 있으면 = 기존 포지션의 연장(추가매수 또는 재시작 reconcile 확정) → 진입 메타 보존.
        // 없으면 = 신규 진입 → durable 에 남아 있던 옛 값(수동 청산 등으로 markSold 를 못 탄 잔재)을 물려받으면
        // 진입 즉시 보유일 초과·과거 고점 기준 트레일링으로 청산돼 왕복 비용만 잃는다.
        val resuming = position
        if (position && !replace) {
            // 추가 매수: 평균 단가 계산. entryStrategy 는 최초 진입 전략 유지(덮어쓰지 않음).
            val totalCost = avgBuyPrice * holdVolume + price * volume
            val totalVolume = holdVolume + volume
            avgBuyPrice = totalCost / totalVolume
            holdVolume = totalVolume
        } else {
            position = true
            avgBuyPrice = price
            holdVolume = volume
            buyDate = if (resuming) buyDate ?: TradingDay.of(now) else TradingDay.of(now)
            entryStrategy = if (resuming) entryStrategy ?: strategy else strategy
            if (!resuming) exitParams = null // 신규 진입 — 진입 시점 스냅샷은 호출부가 다시 찍는다
        }
        // 당일 1회 진입 게이트가 동작하도록 매수 시점에 반드시 set. resetDaily()에서만 해제됨.
        boughtToday = true
        boughtDate = TradingDay.of(now)
        // 신규 진입이면 고점은 진입가에서 다시 시작 — 옛 고점을 물려받으면 첫 tick 부터 트레일링이 발동한다.
        peakPrice = if (resuming) max(peakPrice, price) else price
        lastTradeTime = now
        // H8: 체결 확정 = pending 주문 해소.
        pendingBuyUuid = null
        pendingBuyStrategy = null
    }

    fun markSold(now: LocalDateTime = LocalDateTime.now(TradingDay.KST)) {
        position = false
        avgBuyPrice = 0.0
        holdVolume = 0.0
        clearEntryMeta()
        lastTradeTime = now
        // H8: 청산 시 잔여 pending 도 정리(정상흐름상 이미 null, 방어).
        pendingBuyUuid = null
        pendingBuyStrategy = null
        // 매도 확정 = 매도 pending 해소.
        clearPendingSell()
    }

    /**
     * 신규 진입 시작 시점에 옛 포지션의 진입 메타를 끊는다. 봇 정지 중 거래소에서 직접 청산되면 markSold 를
     * 못 타 durable 에 옛 값이 남는데, 체결 확인 전에 재시작하면 syncPosition 이 position=true 를 먼저 세워
     * markBought 의 "연장" 판정이 그 잔재를 신규 포지션에 물려준다 — 주문 시점에 지워야 그 경로까지 닫힌다.
     */
    fun clearEntryMeta() {
        buyDate = null
        peakPrice = 0.0
        entryStrategy = null
        exitParams = null
    }

    fun clearPendingSell() {
        pendingSellUuid = null
        pendingSellReason = null
        pendingSellVolume = null
        pendingSellAvgPrice = null
        pendingSellSince = null
        pendingSellAlerted = false
    }

    /** #19: 수동 해제 — halt 플래그·사유·실패 카운터를 초기화해 다음 tick 부터 reconcile/매매를 재개한다. */
    fun clearHalt() {
        halted = false
        haltReason = null
        reconcileFailureCount = 0
    }
}
