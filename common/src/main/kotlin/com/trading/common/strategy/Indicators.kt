package com.trading.common.strategy

import com.trading.common.domain.Ohlc
import kotlin.math.max
import kotlin.math.sqrt

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

    fun checkDeadCross(candles: List<Ohlc>, shortPeriod: Int = 5, longPeriod: Int = 20): Boolean {
        if (candles.size < longPeriod + 1) return false
        val shortMa = calculateMa(candles, shortPeriod)
        val longMa = calculateMa(candles, longPeriod)
        // Previous MAs (shift by 1)
        val prevCandles = candles.drop(1)
        val prevShortMa = calculateMa(prevCandles, shortPeriod)
        val prevLongMa = calculateMa(prevCandles, longPeriod)

        // 단기 MA 가 장기 MA 를 하향 교차 — 직전 봉에서 같거나 위였고(>=) 이번 봉에서 아래다.
        return shortMa < longMa && prevShortMa >= prevLongMa
    }

    fun isMaUptrend(candles: List<Ohlc>, shortPeriod: Int = 5, longPeriod: Int = 20): Boolean {
        if (candles.size < longPeriod) return false
        val shortMa = calculateMa(candles, shortPeriod)
        val longMa = calculateMa(candles, longPeriod)
        return shortMa > longMa
    }

    data class BollingerBands(val upper: Double, val middle: Double, val lower: Double, val width: Double)

    fun calculateBollingerBands(candles: List<Ohlc>, period: Int = 20, multiplier: Double = 2.0): BollingerBands? {
        if (candles.size < period) return null
        val closes = candles.take(period).map { it.close }
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

    /**
     * MACD(fast, slow, signal) — TA-Lib(`ta_MACD.c`) 규칙. 받은 히스토리 **전체**를 누적하고, 각 EMA 는 첫 period 개의 SMA 로
     * seed 하되 fast 창은 slow 창의 꼬리(`bars[slow-fast .. slow-1]`)에서 시작한다. 시그널은 slow EMA 가 정의되는 시점부터의
     * MACD 선 전체에 EMA(signal). 최신순 입력(index 0 = 최신), 최소 `slow + signal` 봉(TA-Lib 최소 34 보다 1봉 보수적).
     *
     * 값은 **넘긴 히스토리 길이에 의존**한다 — seed 오차가 `(1-k)^n` 로만 감쇠하므로 넘긴 봉 수가 다른 호출끼리는
     * 서로 조금 다른 값을 본다(수백 봉이면 수렴). 히스토리를 35봉으로 잘라 첫 값으로 seed 하던 이전 구현은 slow EMA 가
     * 아예 수렴하지 못해 표준값과 크게 어긋났다(#27).
     */
    fun calculateMacd(
        candles: List<Ohlc>,
        fastPeriod: Int = 12,
        slowPeriod: Int = 26,
        signalPeriod: Int = 9,
    ): MacdResult? {
        require(fastPeriod in 1 until slowPeriod && signalPeriod >= 1) { "periods must satisfy 1 <= fast < slow, signal >= 1" }
        if (candles.size < slowPeriod + signalPeriod) return null
        val closes = candles.map { it.close }.reversed()

        fun emaSeries(data: List<Double>, period: Int): List<Double> {
            val k = 2.0 / (period + 1)
            val out = ArrayList<Double>(data.size - period + 1)
            var value = data.take(period).average()
            out.add(value)
            for (i in period until data.size) {
                value = data[i] * k + value * (1 - k)
                out.add(value)
            }
            return out
        }

        // fast 창을 slow 창의 꼬리에서 시작해야 두 EMA 가 같은 봉(slow-1)부터 정렬되고 seed 도 TA-Lib 과 같다.
        val fastEma = emaSeries(closes.drop(slowPeriod - fastPeriod), fastPeriod)
        val slowEma = emaSeries(closes, slowPeriod)
        val macdLine = fastEma.zip(slowEma) { f, s -> f - s }
        val signalLine = emaSeries(macdLine, signalPeriod)

        val macd = macdLine.last()
        val signal = signalLine.last()
        return MacdResult(macd = macd, signal = signal, histogram = macd - signal)
    }

    fun calculateEma(candles: List<Ohlc>, period: Int): Double {
        if (candles.size < period) return 0.0
        val closes = candles.take(period).map { it.close }.reversed()
        val k = 2.0 / (period + 1)
        var ema = closes.first()
        for (i in 1 until closes.size) {
            ema = closes[i] * k + ema * (1 - k)
        }
        return ema
    }
}
