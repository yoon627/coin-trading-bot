package com.trading.bot.strategy

import com.trading.bot.config.TradingProperties
import com.trading.bot.domain.Candle
import org.springframework.stereotype.Component

@Component
class RsiBounce : TradingStrategy {
    override val name = "rsi_bounce"

    override suspend fun shouldBuy(
        candles: List<Candle>,
        currentPrice: Double,
        config: TradingProperties,
    ): Boolean {
        if (candles.size < 16) return false

        val currentRsi = Indicators.calculateRsi(candles, 14)
        val prevRsi = Indicators.calculateRsi(candles.drop(1), 14)

        // RSI crossing above oversold level (30)
        return currentRsi > 30.0 && prevRsi <= 30.0
    }
}
