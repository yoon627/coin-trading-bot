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

    fun markBought(price: Double, volume: Double, strategy: String? = null, now: LocalDateTime = LocalDateTime.now(KST)) {
        if (position) {
            // 추가 매수: 평균 단가 계산. entryStrategy 는 최초 진입 전략 유지(덮어쓰지 않음).
            val totalCost = avgBuyPrice * holdVolume + price * volume
            val totalVolume = holdVolume + volume
            avgBuyPrice = totalCost / totalVolume
            holdVolume = totalVolume
        } else {
            position = true
            avgBuyPrice = price
            holdVolume = volume
            buyDate = now.toLocalDate()
            entryStrategy = strategy
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
    }
}
