package com.trading.common.strategy

import com.trading.common.config.TradingProperties
import com.trading.common.domain.Candle
import org.springframework.stereotype.Component

@Component
class GoldenCross : TradingStrategy {
    override val name = "golden_cross"

    override suspend fun shouldBuy(
        candles: List<Candle>,
        currentPrice: Double,
        config: TradingProperties,
    ): Boolean {
        if (candles.size < 21) return false

        val goldenCross = Indicators.checkGoldenCross(candles, 5, 20)
        val rsi = Indicators.calculateRsi(candles, 14)

        // Golden cross + RSI not overbought
        return goldenCross && rsi < 70.0
    }
}
