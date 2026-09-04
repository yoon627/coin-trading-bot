package com.trading.common.strategy

import com.trading.common.config.TradingProperties
import com.trading.common.domain.Candle

/**
 * MFI(14) 가 과매도선 20 을 상향 교차하면 매수 — RSI 에 거래량을 얹은 지표라 반등에 수급이 실렸는지까지 본다.
 */
class MoneyFlowIndex : TradingStrategy {
    override val name = "money_flow_index"

    // MFI(14)+직전 시점 비교는 16봉이면 되지만, override 하지 않는 기본 청산(5/20 데드크로스)이 21봉을 요구한다.
    override val minCandles = 21

    override suspend fun shouldBuy(
        candles: List<Candle>,
        currentPrice: Double,
        config: TradingProperties,
    ): Boolean {
        if (candles.size < minCandles) return false

        val cur = mfi(candles) ?: return false
        val prev = mfi(candles.drop(1)) ?: return false

        return prev <= OVERSOLD && cur > OVERSOLD
    }

    /** 입력은 최신순. 매도 money flow 가 0 이면 비율이 발산하므로 표준 정의대로 100 으로 고정한다. */
    private fun mfi(candles: List<Candle>, period: Int = PERIOD): Double? {
        if (candles.size < period + 1) return null

        var positive = 0.0
        var negative = 0.0
        for (i in 0 until period) {
            val cur = candles[i]
            val tp = typicalPrice(cur)
            val prevTp = typicalPrice(candles[i + 1])
            val flow = tp * cur.candleAccTradeVolume
            when {
                tp > prevTp -> positive += flow
                tp < prevTp -> negative += flow
            }
        }

        // 상승·하락 flow 가 둘 다 0 이면(완전 평탄 구간) MFI 는 정의되지 않는다. 중립값 50 을 돌려주면
        // 그 값이 곧바로 과매도선(20)을 넘은 것으로 읽혀 오신호가 된다 — null 로 신호 자체를 막는다.
        if (positive == 0.0 && negative == 0.0) return null
        if (negative == 0.0) return 100.0
        return 100.0 - (100.0 / (1.0 + positive / negative))
    }

    private fun typicalPrice(candle: Candle): Double =
        (candle.highPrice + candle.lowPrice + candle.tradePrice) / 3.0

    private companion object {
        const val PERIOD = 14
        const val OVERSOLD = 20.0

        /** 가격 변화가 전혀 없어 양·음 flow 가 모두 0 인 구간 — 중립으로 둬 교차 판정에서 빠지게 한다. */
    }
}
