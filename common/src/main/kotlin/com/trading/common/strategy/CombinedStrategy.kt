package com.trading.common.strategy

import com.trading.common.config.TradingProperties
import com.trading.common.domain.Candle

class CombinedStrategy : TradingStrategy {
    override val name = "combined"
    override val minCandles = 21

    override suspend fun shouldBuy(
        candles: List<Candle>,
        currentPrice: Double,
        config: TradingProperties,
    ): Boolean {
        if (candles.size < minCandles) return false

        val targetPrice = Indicators.calculateTargetPrice(candles, config.kValue)
        if (targetPrice <= 0 || currentPrice <= targetPrice) return false

        if (!Indicators.isMaUptrend(candles, 5, 20)) return false

        val rsi = Indicators.calculateRsi(candles, 14)
        return rsi in 30.0..70.0
    }
}
