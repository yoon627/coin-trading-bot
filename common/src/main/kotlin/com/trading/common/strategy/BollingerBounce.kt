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

        // Price was below lower band and now bouncing back above it.
        // currentPrice > prevPrice: 밴드 확장으로 lower 위에 보여도 하락이 이어지면(falling knife) 반등이 아니다.
        val bouncedFromLower = prevPrice <= prevBb.lower && currentPrice > bb.lower && currentPrice > prevPrice

        // RSI confirmation: not deeply oversold (recovering)
        val rsi = Indicators.calculateRsi(candles, 14)
        val rsiOk = rsi in 25.0..45.0

        return bouncedFromLower && rsiOk
    }
}
