package com.trading.common.strategy

import com.trading.common.config.TradingProperties
import com.trading.common.domain.Candle

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
