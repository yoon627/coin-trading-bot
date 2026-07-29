package com.trading.bot.kis.engine

import kotlin.math.max

/**
 * 주식 종목 보유 상태(메모리 캐시). 진실원천은 KIS getHoldings(live) — 이건 tick 평가용.
 * dry-run 에선 getHoldings 가 반영 안 되므로 simulated* 로 메모리 시뮬레이션(plan D18/C1: 무한 재매수 방지).
 */
class StockPosition(val symbol: String) {
    var position: Boolean = false
    var avgBuyPrice: Double = 0.0
    var holdQty: Long = 0
    var peakPrice: Double = 0.0
    var boughtToday: Boolean = false
    var entryStrategy: String? = null

    // 브로커가 매수를 거부(FAILED)하면 진입 슬롯을 소모하지 않는 대신, 이 시각까지 재시도를 멈춘다.
    // 거래정지·계좌제한처럼 반복해도 성공하지 않는 거부가 있어 tick 마다 재전송하면 rate limit·로그가 폭주한다.
    private var buyRetryBlockedUntilNanos: Long = 0
    private var buyFailureCount: Int = 0

    fun canAttemptBuy(): Boolean = System.nanoTime() - buyRetryBlockedUntilNanos >= 0

    fun recordBuyRejection() {
        buyFailureCount += 1
        val delayMs = minOf(BUY_RETRY_BASE_MS shl minOf(buyFailureCount - 1, BUY_RETRY_MAX_SHIFT), BUY_RETRY_CAP_MS)
        buyRetryBlockedUntilNanos = System.nanoTime() + delayMs * 1_000_000
    }

    fun clearBuyRejection() {
        buyFailureCount = 0
        buyRetryBlockedUntilNanos = 0
    }

    fun pnlPercent(price: Long): Double {
        if (avgBuyPrice <= 0) return 0.0
        return ((price - avgBuyPrice) / avgBuyPrice) * 100.0
    }

    fun dropFromPeakPercent(price: Long): Double {
        if (peakPrice <= 0) return 0.0
        return ((peakPrice - price) / peakPrice) * 100.0
    }

    fun updatePeak(price: Long) {
        peakPrice = max(peakPrice, price.toDouble())
    }

    /** live: 실보유로 동기화(getHoldings). qty=0 이면 미보유로 초기화. */
    fun syncFromHolding(qty: Long, avg: Double) {
        if (qty > 0) {
            position = true
            holdQty = qty
            avgBuyPrice = avg
            peakPrice = max(peakPrice, avg)
        } else {
            position = false
            holdQty = 0
            avgBuyPrice = 0.0
            peakPrice = 0.0
        }
    }

    /** dry-run 매수 시뮬레이션 — 재매수 차단(C1). */
    fun markBoughtSimulated(price: Long, strategy: String?) {
        position = true
        avgBuyPrice = price.toDouble()
        holdQty = 0 // 시뮬: 수량은 미상(실주문 아님)
        peakPrice = max(peakPrice, price.toDouble())
        boughtToday = true
        entryStrategy = strategy
    }

    fun markSoldSimulated() {
        position = false
        avgBuyPrice = 0.0
        holdQty = 0
        peakPrice = 0.0
        entryStrategy = null
    }

    private companion object {
        const val BUY_RETRY_BASE_MS = 60_000L
        const val BUY_RETRY_CAP_MS = 1_800_000L // 30분 — 거래정지처럼 당일 내내 안 풀리는 거부를 위한 상한
        const val BUY_RETRY_MAX_SHIFT = 5
    }
}
