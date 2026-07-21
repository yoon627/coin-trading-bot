package com.trading.bot.domain

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.max

data class TradingState(
    val ticker: String,
    var position: Boolean = false,
    var avgBuyPrice: Double = 0.0,
    var holdVolume: Double = 0.0,
    var peakPrice: Double = 0.0,
    var buyDate: LocalDate? = null,
    var boughtToday: Boolean = false,
    var lastTradeTime: LocalDateTime? = null,
    var entryStrategy: String? = null,
    // H8: placeOrder 성공 후 후처리(awaitFill/getAccounts) 실패 시 주문 uuid 를 보존해 다음 tick reconcile.
    var pendingBuyUuid: String? = null,
    var pendingBuyStrategy: String? = null,
    // 매도판 H8: 매도 placeOrder 성공 후 체결확인 실패/미확정 시 uuid·사유 보존 → 다음 tick reconcilePendingSell.
    // 잃으면 뒤늦게 체결된 청산이 기록에서 사라지거나(감사 유실) 재평가로 이중 매도된다.
    var pendingSellUuid: String? = null,
    var pendingSellReason: SellReason? = null,
    // syncPosition 이 거래소 잔고 동기화에 실패하면 true — position 상태가 불확실하므로 매수를 막아
    // 재시작 직후(429 빈발) 이중 포지션을 방지. processTicker 가 다음 tick 재시도해 성공 시 해소.
    var unsynced: Boolean = false,
    // #19: reconcilePendingBuy 의 getOrder·잔고조회가 연속 실패해 pending 이 무한 재시도되면 halt.
    // halt 된 ticker 는 processTicker 초입에서 skip 되어 무한 매수 시도를 멈추고, 수동 해제 전까지 유지된다.
    var halted: Boolean = false,
    var haltReason: String? = null,
    var reconcileFailureCount: Int = 0,
    // 진입 시점 청산 파라미터 스냅샷. 저장·복원 전용 — 소비(진입 시점 값으로 청산)는 strategy-evolution Phase 2.
    var exitParams: ExitParamsSnapshot? = null,
    // durable pending 기록이 실패하면 true — 크래시 시 pending 유실 위험이 있으므로 신규 진입을 막는다(비영속, unsynced 동형).
    var pendingPersistFailed: Boolean = false,
) {
    companion object {
        private val KST: ZoneId = ZoneId.of("Asia/Seoul")
    }

    fun pnlPercent(currentPrice: Double): Double {
        if (avgBuyPrice <= 0) return 0.0
        return ((currentPrice - avgBuyPrice) / avgBuyPrice) * 100.0
    }

    fun dropFromPeakPercent(currentPrice: Double): Double {
        if (peakPrice <= 0) return 0.0
        return ((peakPrice - currentPrice) / peakPrice) * 100.0
    }

    fun updatePeakPrice(currentPrice: Double) {
        peakPrice = max(peakPrice, currentPrice)
    }

    fun resetDaily() {
        // H8: pendingBuyUuid 는 건드리지 않는다(끄면 미해소 주문이 다음날 재매수로 이어져 H8 재발).
        boughtToday = false
    }

    /**
     * @param replace reconcile 확정(재시작 복원)에서 거래소 실잔고를 절대값으로 반영할 때 true. syncPosition 이
     * 이미 position 을 채운 상태에서 추가매수 averaging 으로 이중계상되는 것을 막는다(#20). buyDate·entryStrategy 는
     * durable 복원값이 있으면 유지(재시작으로 진입일/진입전략이 뒤덮이지 않게).
     */
    fun markBought(price: Double, volume: Double, strategy: String? = null, replace: Boolean = false, now: LocalDateTime = LocalDateTime.now(KST)) {
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
            buyDate = buyDate ?: now.toLocalDate()
            entryStrategy = entryStrategy ?: strategy
        }
        // 당일 1회 진입 게이트가 동작하도록 매수 시점에 반드시 set. resetDaily()에서만 해제됨.
        boughtToday = true
        peakPrice = max(peakPrice, price)
        lastTradeTime = now
        // H8: 체결 확정 = pending 주문 해소.
        pendingBuyUuid = null
        pendingBuyStrategy = null
    }

    fun markSold(now: LocalDateTime = LocalDateTime.now(KST)) {
        position = false
        avgBuyPrice = 0.0
        holdVolume = 0.0
        peakPrice = 0.0
        buyDate = null
        entryStrategy = null
        lastTradeTime = now
        // H8: 청산 시 잔여 pending 도 정리(정상흐름상 이미 null, 방어).
        pendingBuyUuid = null
        pendingBuyStrategy = null
        // 매도 확정 = 매도 pending 해소.
        clearPendingSell()
    }

    fun clearPendingSell() {
        pendingSellUuid = null
        pendingSellReason = null
    }

    /** #19: 수동 해제 — halt 플래그·사유·실패 카운터를 초기화해 다음 tick 부터 reconcile/매매를 재개한다. */
    fun clearHalt() {
        halted = false
        haltReason = null
        reconcileFailureCount = 0
    }
}
