package com.trading.common.strategy

import com.trading.common.config.TradingProperties
import com.trading.common.domain.Candle
import com.trading.common.domain.Ohlc
import kotlin.math.abs

/**
 * 슈퍼트렌드(ATR10, 계수 3.0) 추세 반전 진입.
 *
 * 밴드 이월(최종 상단/하단 밴드)이 이 지표의 핵심이라 봉을 과거→현재 순으로 순회해 상태를 누적한다.
 * 청산은 기본 데드크로스를 그대로 쓴다(진입 신호 효과만 분리하기 위한 백테 탐색 전용).
 */
class Supertrend : TradingStrategy {
    override val name = "supertrend"

    // 밴드 이월은 누적 상태라 ATR 최소치(11봉)만으로는 값이 초기값에 좌우된다.
    // 워밍업 30회를 확보하는 41봉으로 두되, 엔진 window(50봉) 상한 안에 들어간다.
    override val minCandles = 41

    private val atrPeriod = 10
    private val multiplier = 3.0

    override suspend fun shouldBuy(
        candles: List<Candle>,
        currentPrice: Double,
        config: TradingProperties,
    ): Boolean {
        if (candles.size < minCandles) return false
        val trends = trendSeries(candles)
        if (trends.size < 2) return false

        // 마지막 봉(= candles[0])에서 하락→상승 전환.
        return trends[trends.size - 2] < 0 && trends.last() > 0
    }

    /**
     * 과거→현재 순으로 계산한 추세 방향(+1 상승 / -1 하락) 시퀀스. 입력 [candles] 는 최신순이다.
     * 앞쪽 ATR 워밍업 구간은 값이 없어 시퀀스에 포함되지 않는다.
     */
    private fun trendSeries(candles: List<Ohlc>): List<Int> {
        val chrono = candles.reversed()
        if (chrono.size < atrPeriod + 2) return emptyList()

        val trueRanges = DoubleArray(chrono.size)
        for (i in 1 until chrono.size) {
            val bar = chrono[i]
            val prevClose = chrono[i - 1].close
            trueRanges[i] = maxOf(
                bar.high - bar.low,
                abs(bar.high - prevClose),
                abs(bar.low - prevClose),
            )
        }

        val trends = mutableListOf<Int>()
        var finalUpper = 0.0
        var finalLower = 0.0
        var trend = 0

        for (i in atrPeriod until chrono.size) {
            var atrSum = 0.0
            for (j in i - atrPeriod + 1..i) atrSum += trueRanges[j]
            val atr = atrSum / atrPeriod

            val bar = chrono[i]
            val hl2 = (bar.high + bar.low) / 2
            val basicUpper = hl2 + multiplier * atr
            val basicLower = hl2 - multiplier * atr

            if (trend == 0) {
                finalUpper = basicUpper
                finalLower = basicLower
                trend = if (bar.close >= hl2) 1 else -1
            } else {
                val prevClose = chrono[i - 1].close
                // 밴드 이월: 밴드가 좁아지는 방향으로만 갱신하고, 종가가 밴드를 넘어서면 이월을 끊는다.
                finalUpper = if (basicUpper < finalUpper || prevClose > finalUpper) basicUpper else finalUpper
                finalLower = if (basicLower > finalLower || prevClose < finalLower) basicLower else finalLower

                trend = when {
                    trend > 0 && bar.close < finalLower -> -1
                    trend < 0 && bar.close > finalUpper -> 1
                    else -> trend
                }
            }
            trends.add(trend)
        }
        return trends
    }
}
