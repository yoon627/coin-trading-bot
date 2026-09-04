package com.trading.common.strategy

import com.trading.common.config.TradingProperties
import com.trading.common.domain.Candle

/**
 * OBV(On-Balance Volume)가 자기 20봉 SMA 를 상향 교차하면 매수 — 가격보다 먼저 도는 수급 전환을 본다.
 *
 * OBV 의 절대 수준은 window 시작점에 따라 달라지지만 OBV 와 그 SMA 가 같은 상수만큼 함께 이동하므로
 * 교차 판정에는 영향이 없다.
 */
class ObvTrend : TradingStrategy {
    override val name = "obv_trend"

    // OBV SMA(20) 을 현재·직전 두 시점에서 비교하려면 OBV 점 21개 = 캔들 21봉.
    override val minCandles = 21

    override suspend fun shouldBuy(
        candles: List<Candle>,
        currentPrice: Double,
        config: TradingProperties,
    ): Boolean {
        if (candles.size < minCandles) return false

        val obv = obvSeries(candles)
        if (obv.size < SMA_PERIOD + 1) return false

        val curObv = obv.last()
        val prevObv = obv[obv.size - 2]
        val curSma = obv.takeLast(SMA_PERIOD).average()
        val prevSma = obv.subList(obv.size - SMA_PERIOD - 1, obv.size - 1).average()

        return curObv > curSma && prevObv <= prevSma
    }

    /** 최신순 입력을 과거순으로 뒤집어 누적한다. 반환값도 과거순(마지막이 최신). */
    private fun obvSeries(candles: List<Candle>): List<Double> {
        val chronological = candles.asReversed()
        val result = mutableListOf(0.0)
        for (i in 1 until chronological.size) {
            val cur = chronological[i]
            val prevClose = chronological[i - 1].tradePrice
            val delta = when {
                cur.tradePrice > prevClose -> cur.candleAccTradeVolume
                cur.tradePrice < prevClose -> -cur.candleAccTradeVolume
                else -> 0.0
            }
            result.add(result.last() + delta)
        }
        return result
    }

    private companion object {
        const val SMA_PERIOD = 20
    }
}
