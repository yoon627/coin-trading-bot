package com.trading.common.strategy

import com.trading.common.config.TradingProperties
import com.trading.common.domain.Candle
import org.springframework.stereotype.Component

@Component
class VolatilityBreakout : TradingStrategy {
    override val name = "volatility_breakout"

    override suspend fun shouldBuy(
        candles: List<Candle>,
        currentPrice: Double,
        config: TradingProperties,
    ): Boolean {
        if (candles.size < 2) return false

        val targetPrice = Indicators.calculateTargetPrice(candles, config.kValue)
        if (targetPrice <= 0) return false

        return currentPrice > targetPrice
    }
}
