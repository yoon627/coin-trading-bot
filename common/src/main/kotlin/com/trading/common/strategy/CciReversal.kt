package com.trading.common.strategy

import com.trading.common.config.TradingProperties
import com.trading.common.domain.Candle
import com.trading.common.domain.Ohlc
import kotlin.math.abs

/**
 * CCI(20) 과매도 반전. 직전 봉 CCI 가 -100 이하였다가 현재 봉이 -100 위로 올라오면 매수.
 *
 * 백테 탐색 전용 — Spring bean 으로 등록하지 않는다.
 */
class CciReversal : TradingStrategy {
    override val name = "cci_reversal"

    // 현재 CCI 에 20봉, 직전 CCI(drop(1))에 1봉 더.
    override val minCandles = PERIOD + 1

    override suspend fun shouldBuy(
        candles: List<Candle>,
        currentPrice: Double,
        config: TradingProperties,
    ): Boolean {
        if (candles.size < minCandles) return false

        val cci = cci(candles) ?: return false
        val prevCci = cci(candles.drop(1)) ?: return false

        return prevCci <= -100.0 && cci > -100.0
    }

    private fun cci(candles: List<Ohlc>): Double? {
        if (candles.size < PERIOD) return null
        val tp = candles.take(PERIOD).map { (it.high + it.low + it.close) / 3.0 }
        val sma = tp.average()
        // 표준 CCI 는 표준편차가 아니라 평균편차(mean absolute deviation)를 쓴다.
        val meanDev = tp.map { abs(it - sma) }.average()
        // 완전 평탄 구간은 편차가 0 이라 나눌 수 없다 — 신호 없음으로 처리.
        if (meanDev <= 0.0) return null
        return (tp.first() - sma) / (0.015 * meanDev)
    }

    private companion object {
        const val PERIOD = 20
    }
}
