package com.trading.bot.strategy

import com.trading.bot.config.TradingProperties
import com.trading.bot.domain.Candle
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
