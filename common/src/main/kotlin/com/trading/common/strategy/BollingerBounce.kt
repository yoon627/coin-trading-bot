package com.trading.common.strategy

import com.trading.common.config.TradingProperties
import com.trading.common.domain.Candle

class BollingerBounce : TradingStrategy {
    override val name = "bollinger_bounce"

    override suspend fun shouldBuy(
        candles: List<Candle>,
        currentPrice: Double,
        config: TradingProperties,
    ): Boolean {
        if (candles.size < 21) return false

        val bb = Indicators.calculateBollingerBands(candles, 20, 2.0) ?: return false
        val prevBb = Indicators.calculateBollingerBands(candles.drop(1), 20, 2.0) ?: return false
        val prevPrice = candles[1].tradePrice

        // Price was below lower band and now bouncing back above it
        val bouncedFromLower = prevPrice <= prevBb.lower && currentPrice > bb.lower

        // RSI confirmation: not deeply oversold (recovering)
        val rsi = Indicators.calculateRsi(candles, 14)
        val rsiOk = rsi in 25.0..45.0

        return bouncedFromLower && rsiOk
    }

    // 진입(하단밴드 반등)의 청산: 종가가 중앙선(SMA20)을 상향 복귀(평균 회귀 완료). candles 종가 기반(currentPrice 미사용).
    override suspend fun shouldSell(
        candles: List<Candle>,
        currentPrice: Double,
        config: TradingProperties,
    ): Boolean {
        if (candles.size < 21) return false

        val bb = Indicators.calculateBollingerBands(candles, 20, 2.0) ?: return false
        val prevBb = Indicators.calculateBollingerBands(candles.drop(1), 20, 2.0) ?: return false

        // 직전 종가는 중앙선 아래, 현재 종가는 중앙선 위로 복귀
        return candles[1].tradePrice < prevBb.middle && candles[0].tradePrice >= bb.middle
    }
}
