package com.trading.common.strategy

import com.trading.common.domain.Ohlc
import kotlin.math.max

object Indicators {

    fun calculateTargetPrice(candles: List<Ohlc>, k: Double = 0.5): Double {
        if (candles.size < 2) return 0.0
        val today = candles[0]
        val yesterday = candles[1]
        val range = yesterday.high - yesterday.low
        return today.open + range * k
    }

    fun calculateRsi(candles: List<Ohlc>, period: Int = 14): Double {
        if (candles.size < period + 1) return 50.0

        // 전체 구간 사용 — take(period+1) 로 자르면 gains.size==period 라 아래 Wilder 루프가
        // 항상 비어 SMA-RSI 로만 계산되던 버그. 전체를 써야 Wilder smoothing 이 실제 적용된다.
        val closes = candles.map { it.close }.reversed()
        val gains = mutableListOf<Double>()
        val losses = mutableListOf<Double>()

        for (i in 1 until closes.size) {
            val change = closes[i] - closes[i - 1]
            gains.add(max(change, 0.0))
            losses.add(max(-change, 0.0))
        }

        var avgGain = gains.take(period).average()
        var avgLoss = losses.take(period).average()

        // Wilder's smoothing for remaining data
        for (i in period until gains.size) {
            avgGain = (avgGain * (period - 1) + gains[i]) / period
            avgLoss = (avgLoss * (period - 1) + losses[i]) / period
        }

        if (avgLoss == 0.0) return 100.0
        val rs = avgGain / avgLoss
        return 100.0 - (100.0 / (1.0 + rs))
    }

    fun calculateMa(candles: List<Ohlc>, period: Int): Double {
        if (candles.size < period) return 0.0
        return candles.take(period).map { it.close }.average()
    }

    fun isMaUptrend(candles: List<Ohlc>, shortPeriod: Int = 5, longPeriod: Int = 20): Boolean {
        if (candles.size < longPeriod) return false
        val shortMa = calculateMa(candles, shortPeriod)
        val longMa = calculateMa(candles, longPeriod)
        return shortMa > longMa
    }
}
