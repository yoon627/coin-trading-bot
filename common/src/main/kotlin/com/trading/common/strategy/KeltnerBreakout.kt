package com.trading.common.strategy

import com.trading.common.config.TradingProperties
import com.trading.common.domain.Candle
import com.trading.common.domain.Ohlc
import kotlin.math.abs

/**
 * 켈트너 채널 상단 돌파 진입.
 *
 * 밴드 = EMA20 ± 2 × ATR10(True Range 단순평균 — Indicators.calculateAtr 과 같은 정의).
 * 청산은 기본 데드크로스를 그대로 쓴다(진입 신호 효과만 분리하기 위한 백테 탐색 전용).
 */
class KeltnerBreakout : TradingStrategy {
    override val name = "keltner_breakout"

    // EMA20 을 직전 봉(drop(1))에서도 구해야 하므로 20 + 1. ATR10 은 11봉이면 되어 상한이 아니다.
    override val minCandles = 21

    private val emaPeriod = 20
    private val atrPeriod = 10
    private val multiplier = 2.0

    override suspend fun shouldBuy(
        candles: List<Candle>,
        currentPrice: Double,
        config: TradingProperties,
    ): Boolean {
        if (candles.size < minCandles) return false

        val upper = upperBand(candles) ?: return false
        val prevUpper = upperBand(candles.drop(1)) ?: return false

        // 직전 봉이 밴드 아래여야 '돌파' 다 — 이미 밴드 위에 있던 연속 봉은 재진입 신호가 아니다.
        return candles[1].tradePrice <= prevUpper && candles[0].tradePrice > upper
    }

    /** 입력은 최신순(index 0 = 최신). 밴드 계산에 필요한 봉이 모자라면 null. */
    private fun upperBand(candles: List<Ohlc>): Double? {
        if (candles.size < emaPeriod || candles.size < atrPeriod + 1) return null
        val atr = atr(candles) ?: return null
        return ema(candles) + multiplier * atr
    }

    private fun ema(candles: List<Ohlc>): Double {
        val closes = candles.take(emaPeriod).map { it.close }.reversed()
        val k = 2.0 / (emaPeriod + 1)
        var value = closes.first()
        for (i in 1 until closes.size) {
            value = closes[i] * k + value * (1 - k)
        }
        return value
    }

    private fun atr(candles: List<Ohlc>): Double? {
        if (candles.size < atrPeriod + 1) return null
        var sum = 0.0
        for (i in 0 until atrPeriod) {
            val current = candles[i]
            val prevClose = candles[i + 1].close
            sum += maxOf(
                current.high - current.low,
                abs(current.high - prevClose),
                abs(current.low - prevClose),
            )
        }
        return sum / atrPeriod
    }
}
