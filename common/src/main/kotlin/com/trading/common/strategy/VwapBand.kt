package com.trading.common.strategy

import com.trading.common.config.TradingProperties
import com.trading.common.domain.Candle

/**
 * 20봉 롤링 VWAP 상향 복귀. 직전 종가가 VWAP 아래였고 현재 종가가 VWAP 위면 매수.
 *
 * 백테 탐색 전용 — Spring bean 으로 등록하지 않는다.
 */
class VwapBand : TradingStrategy {
    override val name = "vwap_band"

    // 현재 VWAP 에 20봉, 직전 VWAP(drop(1))에 1봉 더.
    override val minCandles = PERIOD + 1

    override suspend fun shouldBuy(
        candles: List<Candle>,
        currentPrice: Double,
        config: TradingProperties,
    ): Boolean {
        if (candles.size < minCandles) return false

        val vwap = vwap(candles) ?: return false
        val prevVwap = vwap(candles.drop(1)) ?: return false

        return candles[1].tradePrice < prevVwap && candles[0].tradePrice > vwap
    }

    private fun vwap(candles: List<Candle>): Double? {
        if (candles.size < PERIOD) return null
        val window = candles.take(PERIOD)
        val volume = window.sumOf { it.candleAccTradeVolume }
        if (volume <= 0.0) return null

        // 폴백은 **봉 단위**로 판정한다 — 윈도 합계로 보면 거래대금이 일부 봉에만 채워진 창에서
        // (부분 notional)/(전체 volume) 이 되어 VWAP 이 심하게 과소 계산된다.
        val notional = window.sumOf {
            if (it.candleAccTradePrice > 0.0) it.candleAccTradePrice
            else (it.highPrice + it.lowPrice + it.tradePrice) / 3.0 * it.candleAccTradeVolume
        }
        if (notional <= 0.0) return null
        return notional / volume
    }

    private companion object {
        const val PERIOD = 20
    }
}
