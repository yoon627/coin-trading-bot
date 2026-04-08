package com.trading.bot.strategy

import com.trading.bot.config.TradingProperties
import com.trading.bot.domain.Candle
import org.springframework.stereotype.Component

@Component
class CombinedStrategy : TradingStrategy {
    override val name = "combined"

    override suspend fun shouldBuy(
        candles: List<Candle>,
        currentPrice: Double,
        config: TradingProperties,
    ): Boolean {
        if (candles.size < 21) return false

        // 1. Volatility breakout
        val targetPrice = Indicators.calculateTargetPrice(candles, config.kValue)
        if (targetPrice <= 0 || currentPrice <= targetPrice) return false

        // 2. MA uptrend (short > long)
        if (!Indicators.isMaUptrend(candles, 5, 20)) return false

        // 3. RSI in healthy range (30-70)
        val rsi = Indicators.calculateRsi(candles, 14)
        return rsi in 30.0..70.0
    }
}
