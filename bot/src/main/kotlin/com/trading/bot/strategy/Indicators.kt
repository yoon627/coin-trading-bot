package com.trading.bot.strategy

import com.trading.bot.domain.Candle
import kotlin.math.max
import kotlin.math.sqrt

object Indicators {

    fun calculateTargetPrice(candles: List<Candle>, k: Double = 0.5): Double {
        if (candles.size < 2) return 0.0
        val today = candles[0]
        val yesterday = candles[1]
        val range = yesterday.highPrice - yesterday.lowPrice
        return today.openingPrice + range * k
    }

    fun calculateRsi(candles: List<Candle>, period: Int = 14): Double {
        if (candles.size < period + 1) return 50.0

        val closes = candles.take(period + 1).map { it.tradePrice }.reversed()
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

    fun calculateMa(candles: List<Candle>, period: Int): Double {
        if (candles.size < period) return 0.0
        return candles.take(period).map { it.tradePrice }.average()
    }

    fun checkGoldenCross(candles: List<Candle>, shortPeriod: Int = 5, longPeriod: Int = 20): Boolean {
        if (candles.size < longPeriod + 1) return false
        val shortMa = calculateMa(candles, shortPeriod)
        val longMa = calculateMa(candles, longPeriod)
        // Previous MAs (shift by 1)
        val prevCandles = candles.drop(1)
        val prevShortMa = calculateMa(prevCandles, shortPeriod)
        val prevLongMa = calculateMa(prevCandles, longPeriod)

        return shortMa > longMa && prevShortMa <= prevLongMa
    }

    fun isMaUptrend(candles: List<Candle>, shortPeriod: Int = 5, longPeriod: Int = 20): Boolean {
        if (candles.size < longPeriod) return false
        val shortMa = calculateMa(candles, shortPeriod)
        val longMa = calculateMa(candles, longPeriod)
        return shortMa > longMa
    }

    data class BollingerBands(val upper: Double, val middle: Double, val lower: Double, val width: Double)

    fun calculateBollingerBands(candles: List<Candle>, period: Int = 20, multiplier: Double = 2.0): BollingerBands? {
        if (candles.size < period) return null
        val closes = candles.take(period).map { it.tradePrice }
        val middle = closes.average()
        val variance = closes.map { (it - middle) * (it - middle) }.average()
        val stdDev = sqrt(variance)
        return BollingerBands(
            upper = middle + stdDev * multiplier,
            middle = middle,
            lower = middle - stdDev * multiplier,
            width = if (middle > 0) (stdDev * multiplier * 2) / middle else 0.0,
        )
    }

    data class MacdResult(val macd: Double, val signal: Double, val histogram: Double)

    fun calculateMacd(
        candles: List<Candle>,
        fastPeriod: Int = 12,
        slowPeriod: Int = 26,
        signalPeriod: Int = 9,
    ): MacdResult? {
        val needed = slowPeriod + signalPeriod
        if (candles.size < needed) return null
        val closes = candles.take(needed).map { it.tradePrice }.reversed()

        fun ema(data: List<Double>, period: Int): List<Double> {
            val k = 2.0 / (period + 1)
            val result = mutableListOf(data.first())
            for (i in 1 until data.size) {
                result.add(data[i] * k + result.last() * (1 - k))
            }
            return result
        }

        val fastEma = ema(closes, fastPeriod)
        val slowEma = ema(closes, slowPeriod)
        val macdLine = fastEma.zip(slowEma).map { (f, s) -> f - s }
        val signalLine = ema(macdLine.takeLast(signalPeriod + 5), signalPeriod)

        val macd = macdLine.last()
        val signal = signalLine.last()
        return MacdResult(macd = macd, signal = signal, histogram = macd - signal)
    }

    fun calculateStdDev(candles: List<Candle>, period: Int): Double {
        if (candles.size < period) return 0.0
        val closes = candles.take(period).map { it.tradePrice }
        val mean = closes.average()
        return sqrt(closes.map { (it - mean) * (it - mean) }.average())
    }

    fun calculateEma(candles: List<Candle>, period: Int): Double {
        if (candles.size < period) return 0.0
        val closes = candles.take(period).map { it.tradePrice }.reversed()
        val k = 2.0 / (period + 1)
        var ema = closes.first()
        for (i in 1 until closes.size) {
            ema = closes[i] * k + ema * (1 - k)
        }
        return ema
    }
}
