package com.trading.common.strategy

import com.trading.common.config.TradingProperties
import com.trading.common.domain.Candle
import com.trading.common.domain.Ohlc

/**
 * 스토캐스틱 %K(14)가 %D(3봉 SMA)를 상향 교차하고, 그 교차가 과매도 구간에서 일어났을 때 매수.
 *
 * 백테 탐색 전용 — Spring bean 으로 등록하지 않는다.
 */
class StochasticCross : TradingStrategy {
    override val name = "stochastic_cross"

    // 진입에 필요한 건 17봉이지만, 기본 shouldSell(5/20 데드크로스)이 21봉을 요구한다.
    override val minCandles = 21

    override suspend fun shouldBuy(
        candles: List<Candle>,
        currentPrice: Double,
        config: TradingProperties,
    ): Boolean {
        if (candles.size < K_PERIOD + D_PERIOD) return false

        val k = percentK(candles, 0) ?: return false
        val prevK = percentK(candles, 1) ?: return false
        val d = percentD(candles, 0) ?: return false
        val prevD = percentD(candles, 1) ?: return false

        return prevK <= prevD && k > d && k < OVERSOLD
    }

    /** [offset] 봉(0=최신)을 현재봉으로 보는 %K. 입력이 최신순이라 window 는 offset 부터 과거 방향으로 K_PERIOD 개다. */
    private fun percentK(candles: List<Ohlc>, offset: Int): Double? {
        if (candles.size < offset + K_PERIOD) return null
        val window = candles.subList(offset, offset + K_PERIOD)
        val highest = window.maxOf { it.high }
        val lowest = window.minOf { it.low }
        val range = highest - lowest
        // 무변동 구간에서 %K 는 정의되지 않는다 — 임의값으로 교차를 만들지 말고 신호를 버린다.
        if (range <= 0.0) return null
        return (window[0].close - lowest) / range * 100.0
    }

    private fun percentD(candles: List<Ohlc>, offset: Int): Double? {
        var sum = 0.0
        for (i in 0 until D_PERIOD) {
            sum += percentK(candles, offset + i) ?: return null
        }
        return sum / D_PERIOD
    }

    private companion object {
        const val K_PERIOD = 14
        const val D_PERIOD = 3
        const val OVERSOLD = 30.0
    }
}
