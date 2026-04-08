package com.trading.bot.domain

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

data class TradingState(
    val ticker: String,
    var position: Boolean = false,
    var avgBuyPrice: Double = 0.0,
    var holdVolume: Double = 0.0,
    var buyDate: LocalDate? = null,
    var boughtToday: Boolean = false,
    var lastTradeTime: LocalDateTime? = null,
) {
    fun pnlPercent(currentPrice: Double): Double {
        if (avgBuyPrice <= 0) return 0.0
        return ((currentPrice - avgBuyPrice) / avgBuyPrice) * 100.0
    }

    fun resetDaily() {
        boughtToday = false
    }

    fun markBought(price: Double, volume: Double) {
        position = true
        avgBuyPrice = price
        holdVolume = volume
        buyDate = LocalDate.now(ZoneId.of("Asia/Seoul"))
        boughtToday = true
        lastTradeTime = LocalDateTime.now(ZoneId.of("Asia/Seoul"))
    }

    fun markSold() {
        position = false
        avgBuyPrice = 0.0
        holdVolume = 0.0
        buyDate = null
        lastTradeTime = LocalDateTime.now(ZoneId.of("Asia/Seoul"))
    }
}
